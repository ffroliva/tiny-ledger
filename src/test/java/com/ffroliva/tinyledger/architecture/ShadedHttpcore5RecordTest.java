package com.ffroliva.tinyledger.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The suppression's re-check trigger, made executable.
 *
 * <p>{@code .github/owasp-suppressions.xml} suppresses CVE-2026-54399 and CVE-2026-54428 against
 * {@code httpcore5 5.0.2}, which is not a declared dependency at all: it is shaded inside
 * {@code docker-java-transport-zerodep}, the transport Testcontainers uses to reach the Docker
 * daemon. The entry's only review mechanism was a sentence — "<em>Re-check when the testcontainers
 * BOM moves past 1.20.5</em>" — and when the BOM did move, to 1.21.4, nothing re-checked. The
 * suppression stayed green while the sentence quietly became false.
 *
 * <p>This turns that sentence into a gate. It fails the moment the shaded version moves, which is
 * the moment the suppression might be deletable rather than re-dated — and deleting a suppression is
 * the outcome nobody remembers to go looking for.
 *
 * <p>Measured, not assumed: zerodep 3.4.1 (testcontainers 1.20.5) and 3.4.2 (1.21.4) both shade
 * 5.0.2, so the upgrade the note prescribed was tried and did not fix it. zerodep <b>3.7.1</b> does
 * shade a fixed 5.3.6 — but testcontainers 1.21.4 version-locks {@code docker-java-api} and the
 * transport together at 3.4.2, so pinning the transport alone skews that pair three minor lines and
 * needs the full IT suite to justify. That is a deliberate follow-up, not a drive-by.
 */
class ShadedHttpcore5RecordTest {

    /** What zerodep shades today. When this changes, revisit the suppression. */
    private static final String RECORDED_HTTPCORE5 = "5.0.2";

    private static final String SHADED_POM_PROPERTIES =
            "META-INF/maven/org.apache.httpcomponents.core5/httpcore5/pom.properties";

    @Test
    void shadedHttpcore5IsStillTheVersionTheSuppressionNames() {
        Properties shaded = shadedHttpcore5Metadata();
        assertThat(shaded.getProperty("version"))
                .as("the httpcore5 shaded into docker-java-transport-zerodep moved. Re-read "
                        + ".github/owasp-suppressions.xml: if the new version fixes "
                        + "CVE-2026-54399 / CVE-2026-54428 the suppression should be "
                        + "DELETED, not re-dated.")
                .isEqualTo(RECORDED_HTTPCORE5);
    }

    private static Properties shadedHttpcore5Metadata() {
        ClassLoader loader = ShadedHttpcore5RecordTest.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(SHADED_POM_PROPERTIES)) {
            assertThat(in)
                    .as(
                            "%s is not on the test classpath — the transport stopped shading "
                                    + "httpcore5, which is itself worth reading the suppression over",
                            SHADED_POM_PROPERTIES)
                    .isNotNull();
            Properties p = new Properties();
            p.load(in);
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + SHADED_POM_PROPERTIES, e);
        }
    }
}
