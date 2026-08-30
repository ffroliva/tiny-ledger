package com.ffroliva.tinyledger.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The Ryuk sidecar image, recorded so it cannot move in silence.
 *
 * <p>{@code AbstractIntegrationTest} starts Postgres, Redis, Kafka and Keycloak in a {@code static}
 * block and never stops them. Ryuk is the only thing that reaps them, so a Ryuk change leaks four
 * containers per IT run on a shared runner. Its image ships <em>inside</em> the Testcontainers jar
 * and is named nowhere in this tree, which is how it moved: bumping
 * {@code <testcontainers.version>} 1.20.5 → 1.21.4 in one line also moved Ryuk 0.11.0 → 0.12.0,
 * with nothing in the diff saying so. {@code pom.xml} states the policy every other image already
 * follows — "<em>Every other image here is pinned … Bumping means changing the digests
 * deliberately, which is the point</em>" — and this one sat outside it.
 *
 * <p><strong>Why this reads bytecode instead of asking an API.</strong> There is no API to ask.
 * Measured against testcontainers 1.21.4: {@code RyukContainer} carries the image as a hardcoded
 * constant and contains no reference to {@code ryuk.container.image}, {@code getEnvVarOrProperty} or
 * {@code getImage} — so a {@code testcontainers.properties} pin is a no-op for it, and adding one
 * would have looked like a fix while changing nothing. {@code TestcontainersConfiguration
 * .getRyukImage()} <em>does</em> read that property, but it is not what starts Ryuk and defaults to
 * the floating {@code testcontainers/ryuk:latest}; asserting on it would be asserting on a value
 * this project never uses. The class constant is the only thing that decides which Ryuk runs, so it
 * is the only honest thing to assert on.
 *
 * <p>This records rather than pins, because pinning is not on offer. When a Testcontainers bump
 * moves the constant, this test fails and updating {@link #RECORDED_RYUK_IMAGE} is the deliberate
 * act the pom's policy asks for. No Docker daemon is touched — verified by running it with
 * {@code DOCKER_HOST} pointed at a socket that does not exist.
 */
class RyukImageRecordTest {

    /** The Ryuk shipped by the current {@code <testcontainers.version>}. Update deliberately. */
    private static final String RECORDED_RYUK_IMAGE = "testcontainers/ryuk:0.12.0";

    private static final String RYUK_CLASS = "org/testcontainers/utility/RyukContainer.class";
    private static final Pattern RYUK_IMAGE = Pattern.compile("testcontainers/ryuk:[\\w.+-]+");

    @Test
    void ryukImageIsTheOneThisProjectHasRecorded() {
        Matcher found = RYUK_IMAGE.matcher(ryukContainerConstantPool());
        assertThat(found.find())
                .as(
                        "no testcontainers/ryuk:<tag> literal in %s — Testcontainers changed how "
                                + "Ryuk names its image, so this gate is reading the wrong place",
                        RYUK_CLASS)
                .isTrue();
        assertThat(found.group())
                .as("Ryuk moved with a Testcontainers bump. It reaps every container "
                        + "AbstractIntegrationTest leaves running, so record the new image "
                        + "here on purpose rather than letting it move unremarked.")
                .isEqualTo(RECORDED_RYUK_IMAGE);
    }

    /**
     * {@code RyukContainer.class} as text. Class-file string constants are modified UTF-8; the image
     * literal is ASCII, so an ISO-8859-1 view of the bytes exposes it without a parser.
     */
    private static String ryukContainerConstantPool() {
        ClassLoader loader = RyukImageRecordTest.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(RYUK_CLASS)) {
            assertThat(in).as("%s is not on the test classpath", RYUK_CLASS).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RYUK_CLASS, e);
        }
    }
}
