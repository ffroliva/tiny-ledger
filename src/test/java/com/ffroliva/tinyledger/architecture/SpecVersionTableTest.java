package com.ffroliva.tinyledger.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * §1.5's stack table, fenced against the files it names.
 *
 * <p>The table has drifted three times. Twice it was repaired by hand, and {@code CHANGELOG.md}
 * closes the second repair with "<em>No gate was added — nothing in CI checks documentation here
 * (§8.4), so the mechanism that let these accumulate is unchanged</em>". It then drifted a third
 * time exactly as that sentence predicted: Dependabot moved Testcontainers to 1.21.4 and the Boot
 * parent to 4.1.1 while the table still read 1.20.5 and 4.1.0. This is the gate that sentence said
 * was missing, in the same shape {@link WorkflowGovernanceTest} uses for {@code ci.yml}.
 *
 * <p>Only rows naming an exact version <em>and</em> a machine-readable source are covered. Rows that
 * name a line rather than a version — Spring Modulith's "Boot-4 line", Hibernate's "managed by the
 * Boot parent", the Maven wrapper's "3.8+" — have nothing to compare against and are deliberately
 * left alone rather than given a check that cannot fail.
 */
class SpecVersionTableTest {

    private static final Path SPEC = Path.of("docs", "spec.md");
    private static final Path POM = Path.of("pom.xml");
    private static final Path COMPOSE = Path.of("docker", "docker-compose.yml");
    private static final Path IT_BASE = Path.of(
            "src/test/java/com/ffroliva/tinyledger/testsupport/AbstractIntegrationTest.java");

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("spec-version gate cannot read " + file, e);
        }
    }

    /** The bolded version in §1.5's table row for {@code component}. */
    private static String specRow(String component) {
        Matcher m = Pattern.compile(
                        "^\\| " + Pattern.quote(component) + " \\| \\*\\*([^*]+)\\*\\*",
                        Pattern.MULTILINE)
                .matcher(read(SPEC));
        assertThat(m.find())
                .as("§1.5 has a bolded-version row for %s — did the table shape change?", component)
                .isTrue();
        return m.group(1);
    }

    /** First capture of {@code regex} in {@code file}, asserted present so a rename fails loudly. */
    private static String capture(Path file, String regex) {
        Matcher m = Pattern.compile(regex).matcher(read(file));
        assertThat(m.find()).as("%s no longer matches anything in %s", regex, file).isTrue();
        return m.group(1);
    }

    @Test
    void javaRowMatchesThePomProperty() {
        assertThat(specRow("Java"))
                .as("§1.5 Java row vs <java.version>")
                .isEqualTo(capture(POM, "<java\\.version>([^<]+)</java\\.version>"));
    }

    @Test
    void springBootRowMatchesTheParentVersion() {
        String parent = capture(
                POM,
                "<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>");
        assertThat(specRow("Spring Boot"))
                .as("§1.5 Spring Boot row vs the <parent> version")
                .isEqualTo(parent);
    }

    @Test
    void testcontainersRowMatchesThePomProperty() {
        assertThat(specRow("Testcontainers"))
                .as("§1.5 Testcontainers row vs <testcontainers.version>")
                .isEqualTo(capture(POM, "<testcontainers\\.version>([^<]+)</testcontainers\\.version>"));
    }

    @Test
    void postgresRowMatchesBothImagePins() {
        // The row's own note is the claim under test: "postgres:16-alpine in BOTH
        // docker/docker-compose.yml and AbstractIntegrationTest, so Compose and the ITs
        // exercise one version". Two pins, one row, and nothing kept them together.
        String expected = specRow("PostgreSQL") + "-alpine";
        assertThat(capture(COMPOSE, "image: postgres:([\\w.-]+)"))
                .as("§1.5 PostgreSQL row vs the Compose image")
                .isEqualTo(expected);
        assertThat(capture(IT_BASE, "\"postgres:([\\w.-]+)\""))
                .as("§1.5 PostgreSQL row vs the AbstractIntegrationTest image")
                .isEqualTo(expected);
    }
}
