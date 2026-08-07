package com.ffroliva.tinyledger.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * A properties-file assertion rather than a context test, and deliberately so: the claim is about
 * <em>which file</em> declares the key.
 *
 * <p>§6.6 records JSON logs as a {@code full}-only narrowing (v3.41). The way that decision gets
 * silently reversed is someone moving this line into the base file, where it would turn every local
 * {@code ./mvnw verify} and every CI failure log into JSON — a cost paid on every run for a benefit
 * taken in production. <strong>The base-file assertion is the half that matters</strong>; the
 * {@code full} one alone would stay green through exactly that mistake.
 *
 * <p>The correlation pattern is asserted in the base file for the mirror-image reason: it is what keeps
 * {@code standalone}'s human-readable console correlatable, so the narrowing above costs nothing a
 * reader needs. It carries the FAPI interaction id too — see {@link FapiInteractionIdFilter}, which had
 * to give up the MDC key {@code traceId} when Micrometer Tracing claimed it.
 */
class StructuredLoggingTest {

    private static Properties read(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources", name))) {
            properties.load(in);
        }
        return properties;
    }

    @Test
    void fullLogsJsonAndTheBaseProfileDoesNot() throws IOException {
        assertThat(read("application-full.properties").getProperty("logging.structured.format.console"))
                .isEqualTo("logstash");
        assertThat(read("application.properties").getProperty("logging.structured.format.console"))
                .as("§6.6 v3.41: JSON is a `full`-only narrowing; in the base file it would hit every verify")
                .isNull();
        assertThat(read("application-standalone.properties").getProperty("logging.structured.format.console"))
                .isNull();
    }

    @Test
    void everyLineCarriesTheTraceSpanAndInteractionIdsInBothModes() throws IOException {
        assertThat(read("application.properties").getProperty("logging.pattern.correlation"))
                .as("§6.6: every log line carries trace_id and span_id — plus the interaction id, which"
                        + " Boot's default pattern would drop after FapiInteractionIdFilter moved keys")
                .contains("%X{traceId")
                .contains("%X{spanId")
                .contains("%X{interactionId");
    }
}
