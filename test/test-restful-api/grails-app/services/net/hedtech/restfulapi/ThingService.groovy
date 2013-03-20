/* ****************************************************************************
Copyright 2013 Ellucian Company L.P. and its affiliates.
******************************************************************************/
package net.hedtech.restfulapi


import grails.validation.ValidationException

import org.codehaus.groovy.grails.web.util.WebUtils

import org.hibernate.StaleObjectStateException

import org.springframework.dao.OptimisticLockingFailureException

import org.springframework.orm.hibernate3.HibernateOptimisticLockingFailureException as OptimisticLockException

import java.security.*

class ThingService {

    def list(Map params) {

        log.trace "ThingService.list invoked with params $params"

        def result

        // TODO: Do validation testing in create or update -- this is temporary
        if (params.forceValidationError == 'y') {
            // This will throw a validation exception...
            new Thing(code:'FAIL', description: 'Code exceeds 2 chars').save(failOnError:true)
        }

        params.max = Math.min( params.max ? params.max.toInteger() : 10,  100)
        result = Thing.list(fetch: [parts: "eager"],
                            max: params.max, offset: params.offset ).sort { it.id }
        result.each {
            supplementThing( it )
        }

        log.trace "ThingService.list returning ${result}"
        result
    }

    def count() {
        log.trace "ThingService.count invoked"
        Thing.count()
    }


    def show(Map params) {
        log.trace "ThingService.show invoked"
        def result
        result = Thing.get(params.id)
        result.parts // force lazy loading
        supplementThing( result )
        log.trace "ThingService.show returning ${result}"
        result
    }

    def create(Map content) {
        log.trace "ThingService.create invoked"

        if (WebUtils.retrieveGrailsWebRequest().getParameterMap().forceGenericError == 'y') {
            throw new Exception( "generic failure" )
        }

        def result

        Thing.withTransaction {
            def instance = new Thing( content )
            instance.parts = [] as Set //workaround for GRAILS-9775 until the bindable constraint works on associtations
            content['parts'].each { partID ->
                instance.addPart( PartOfThing.get( partID ) )
            }
            instance.save(failOnError:true)
            result = instance
            result.parts //force lazy loading
        }
        supplementThing( result )
        result
    }

    def update(def id, Map content) {
        log.trace "ThingService.update invoked"

        checkForExceptionRequest()

        def result
        Thing.withTransaction {
            def thing = Thing.get(id)

            checkOptimisticLock( thing, content )

            thing.properties = content
            thing.save(failOnError:true)
            result = thing
            result.parts //force lazy loading
        }
        supplementThing( result )
        result
    }

    void delete(id,Map content) {
        Thing.withTransaction {
            def thing = Thing.get(id)
            thing.delete(failOnError:true)
        }
    }

    public def checkOptimisticLock( domainObject, content ) {

        if (domainObject.hasProperty( 'version' )) {
            if (!content?.version) {
                thing.errors.reject( 'version', "net.hedtech.restfulapi.Thing.missingVersion")
                throw new ValidationException( "Missing version field", thing.errors )
            }
            int ver = content.version instanceof String ? content.version.toInteger() : content.version
            if (ver != domainObject.version) {
                throw exceptionForOptimisticLock( domainObject, content )
            }
        }
    }

    private def exceptionForOptimisticLock( domainObject, content ) {
        new OptimisticLockException( new StaleObjectStateException( domainObject.class.getName(), domainObject.id ) )
    }

    /**
     * Checks the request for a flag asking for a specific exception to be thrown
     * so error handling can be tested.
     * This is a method to support testing of the plugin, and should not be taken
     * as an example of good service construction.
     **/
    private void checkForExceptionRequest() {
        def params = WebUtils.retrieveGrailsWebRequest().getParameterMap()
        if (params.throwOptimisticLock == 'y') {
            throw new OptimisticLockingFailureException( "requested optimistic lock for testing" )
        }
        if (params.throwApplicationException == 'y') {
            throw new DummyApplicationException( params.appStatusCode, params.appMsgCode, params.appErrorType )
        }
    }

    private void supplementThing( Thing thing ) {
        MessageDigest digest = MessageDigest.getInstance("SHA1")
        digest.update("code:${thing.getCode()}".getBytes("UTF-8"))
        digest.update("description${thing.getDescription()}".getBytes("UTF-8"))
        def properties = [sha1:new BigInteger(1,digest.digest()).toString(16).padLeft(40,'0')]
        thing.metaClass.getSupplementalRestProperties << {-> properties }
    }
}
