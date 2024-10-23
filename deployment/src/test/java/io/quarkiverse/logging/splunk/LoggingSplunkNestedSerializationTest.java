/*
Copyright (c) 2021 Amadeus s.a.s.
Contributor(s): Kevin Viet, Romain Quinio (Amadeus s.a.s.)
 */
package io.quarkiverse.logging.splunk;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.RegexBody.regex;

import java.util.Arrays;
import java.util.logging.Handler;
import java.util.logging.Logger;

import org.jboss.logging.MDC;
import org.jboss.logmanager.ExtHandler;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockserver.verify.VerificationTimes;

import io.quarkus.bootstrap.logging.InitialConfigurator;
import io.quarkus.test.QuarkusUnitTest;

class LoggingSplunkNestedSerializationTest extends AbstractMockServerTest {

    @RegisterExtension
    static final QuarkusUnitTest unitTest = withMockServerConfig()
            .withConfigurationResource("application-splunk-logging-nested.properties")
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class));

    static final org.jboss.logging.Logger logger = org.jboss.logging.Logger
            .getLogger(LoggingSplunkNestedSerializationTest.class);

    @Test
    void handlerShouldBeCreated() {
        ExtHandler delayedHandler = InitialConfigurator.DELAYED_HANDLER;
        assertThat(Logger.getLogger("").getHandlers(), hasItemInArray(delayedHandler));
        Handler handler = Arrays.stream(delayedHandler.getHandlers())
                .filter(h -> (h instanceof SplunkLogHandler))
                .findFirst().orElse(null);
        assertThat(handler, notNullValue());
    }

    @Test
    void handlerShouldFormatMessage() {
        logger.warnv("hello {0}", "splunk!");
        awaitMockServer();
        httpServer.verify(requestToJsonEndpoint().withBody(json("{ event: { message: 'hello splunk!' }}")));
    }

    @Test
    void eventIsAJsonObjectWithMetadata() {
        logger.warn("hello splunk");
        awaitMockServer();
        httpServer.verify(requestToJsonEndpoint().withBody(json("{ event: { message: 'hello splunk' }}")));
    }

    @Test
    void eventHasStandardMetadata() {
        logger.warn("hello splunk");
        awaitMockServer();
        httpServer.verify(requestToJsonEndpoint().withBody(json(
                "{ source: 'mysource', sourcetype: 'mysourcetype', index: 'myindex'} "))
                .withBody(regex(".*host.*")));
    }

    @Test
    void tokenIsSentAsAuthorizationHeader() {
        logger.warn("hello splunk");
        awaitMockServer();
        httpServer
                .verify(requestToJsonEndpoint().withHeader("Authorization", "Splunk 12345678-1234-1234-1234-1234567890AB"));
    }

    @Test
    void clientAddsMinimalMetadata() {
        logger.warn("hello splunk");
        awaitMockServer();
        httpServer.verify(requestToJsonEndpoint().withBody(json(
                "{ event: { message: 'hello splunk', severity:'WARN' }}")));
    }

    @Test
    void mdcFieldsShouldBeSentAsMetadata() {
        MDC.put("mdc-key", "mdc-value");
        logger.warn("hello mdc");
        MDC.remove("mdc-key");
        awaitMockServer();
        httpServer.verify(
                requestToJsonEndpoint()
                        .withBody(json("{ event: { message: 'hello mdc', properties: { 'mdc-key': 'mdc-value' }}}")));
    }

    @Test
    void messageShouldContainException() {
        logger.error("unexpected error", new RuntimeException("test exception"));
        awaitMockServer();
        httpServer.verify(requestToJsonEndpoint()
                .withBody(regex(".*unexpected error: java.lang.RuntimeException: test exception.*")));
    }

    @Test
    void logLevelShouldBeUsed() {
        logger.info("Info log");
        httpServer.verify(requestToJsonEndpoint().withBody(json("{ event: { message: 'Info log' }}")),
                VerificationTimes.exactly(0));
    }

    @Test
    void clientAddsStructuredMetadata() {
        logger.error("hello splunk", new RuntimeException("test exception"));
        awaitMockServer();
        httpServer.verify(requestToJsonEndpoint().withBody(regex(".*hello splunk.*")));
        httpServer.verify(requestToJsonEndpoint().withBody(json(
                "{ event: { logger:'io.quarkiverse.logging.splunk.LoggingSplunkNestedSerializationTest', " +
                        "exception: 'test exception' }}")));
        httpServer.verify(requestToJsonEndpoint().withBody(regex(".*thread.*")));
    }

    @Test
    void staticMetadataFields() {
        logger.warn("hello splunk");
        awaitMockServer();
        httpServer.verify(requestToJsonEndpoint().withBody(
                json("{ fields: { metadata-0: 'value0', metadata-1: 'value1' } }")));
    }

    @Test
    void nestedJsonMessageIsParsed() {
        logger.warn("{ 'greeting': 'hello', 'user': 'splunk' }");
        awaitMockServer();
        httpServer.verify(
                requestToJsonEndpoint().withBody(json("{ event: { message: { 'greeting': 'hello', 'user': 'splunk' }}}")));
    }
}
