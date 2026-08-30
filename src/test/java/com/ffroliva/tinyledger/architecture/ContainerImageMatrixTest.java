package com.ffroliva.tinyledger.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every container image, named in four places, held to one version.
 *
 * <p>The same backing services are spelled in the Testcontainers base class, the Compose file, the
 * local-kind overlay and stage 11d's Trivy ceiling table. Nothing tied them together. They agree
 * today only because a human moved them together, and the failure when they drift is not loud: the
 * ITs would prove one Postgres while Compose ran another, and stage 11d would gate a CVE count for
 * an image nothing starts.
 *
 * <p>This is the same shape as stage 1's app-image-tag check, which asserts {@code tiny-ledger:*}
 * agrees across five files, and it exists for the same reason. That gate's own comment records what
 * happened without it: "a version bump moved the built image to a new tag while the other three kept
 * naming the old one: compose started a STALE image and the e2e guard confirmed it was present,
 * because it was." The backing services had no equivalent.
 *
 * <p><b>What it does not do.</b> It does not say an image is current — {@code redis:7-alpine} while
 * 8 exists passes here, correctly, because being behind is a decision and disagreeing is a defect.
 * Currency is Dependabot's job, and stage 11d's ceilings judge the vulnerabilities of whatever is
 * pinned. This layer only refuses to let the four sites disagree.
 *
 * <p>An image named by a single site is left alone: the collector and Traefik are Compose-only by
 * design, and requiring them everywhere would be inventing a rule rather than enforcing one.
 */
class ContainerImageMatrixTest {

    /** The application's own locally built tag. Stage 1 in ci.yml already governs it across 5 files. */
    private static final Set<String> NOT_A_TRACKED_IMAGE = Set.of("tiny-ledger");

    /** Anything shaped like {@code name:tag}, optionally digest-pinned. Deliberately strict so a
     * timestamp or a JDBC URL in the same file cannot be mistaken for an image reference. */
    private static final Pattern IMAGE_REF =
            Pattern.compile("[a-z0-9][a-z0-9._/-]*:[A-Za-z0-9][A-Za-z0-9._-]*(@sha256:[a-f0-9]{64})?");

    private record Site(String label, Path file, Pattern extractor) {}

    private static final List<Site> SITES = List.of(
            new Site(
                    "Testcontainers (AbstractIntegrationTest)",
                    Path.of("src/test/java/com/ffroliva/tinyledger/testsupport/AbstractIntegrationTest.java"),
                    Pattern.compile("\"([^\"]+)\"")),
            new Site(
                    "docker-compose",
                    Path.of("docker", "docker-compose.yml"),
                    Pattern.compile("(?m)^\\s*image:\\s*(\\S+)")),
            new Site(
                    "k8s local-kind overlay",
                    Path.of("deploy/k8s/overlays/local-kind/backing-services.yaml"),
                    Pattern.compile("(?m)^\\s*image:\\s*(\\S+)")),
            // Stage 11d's ceilings are a bash `case`: `<image:tag>) echo N ;;`. A ceiling keyed to an
            // image nobody runs is dead, and an image with no ceiling fails that stage with
            // "no pinned ceiling" — so the table is a fourth site and belongs in the matrix.
            new Site(
                    "Trivy ceilings (ci.yml stage 11d)",
                    Path.of(".github", "workflows", "ci.yml"),
                    Pattern.compile("(?m)^\\s*([a-z0-9][^)\\s]*:[^)\\s]+)\\)\\s+echo")));

    @Test
    void everyImageAgreesAcrossEverySiteThatNamesIt() {
        Map<String, Map<String, String>> matrix = buildMatrix();

        List<String> disagreements = new ArrayList<>();
        for (var entry : matrix.entrySet()) {
            Map<String, String> bySite = entry.getValue();
            long distinct = bySite.values().stream().distinct().count();
            if (bySite.size() > 1 && distinct > 1) {
                disagreements.add("  %s -> %s".formatted(entry.getKey(), bySite));
            }
        }

        assertThat(disagreements)
                .as(
                        "these images are spelled differently in different places. Every site that "
                                + "names an image must name the SAME version, or the suite proves one "
                                + "thing and Compose runs another.%n%s",
                        renderMatrix(matrix))
                .isEmpty();
    }

    @Test
    void theMatrixIsNotAccidentallyEmpty() {
        // A parser that matches nothing exits green, which is the failure mode this repository
        // names as trap 4 in AGENTS.md. The gate asserts it found real images at every site.
        Map<String, Map<String, String>> matrix = buildMatrix();
        assertThat(matrix)
                .as("no container images parsed at all — the extractors broke%n%s", renderMatrix(matrix))
                .isNotEmpty();
        for (Site site : SITES) {
            assertThat(matrix.values().stream().anyMatch(m -> m.containsKey(site.label())))
                    .as("no image parsed from %s (%s) — its extractor no longer matches", site.label(), site.file())
                    .isTrue();
        }
    }

    /** image name -> (site label -> tag). */
    private static Map<String, Map<String, String>> buildMatrix() {
        Map<String, Map<String, String>> matrix = new TreeMap<>();
        for (Site site : SITES) {
            Matcher m = site.extractor().matcher(read(site.file()));
            while (m.find()) {
                String ref = m.group(1);
                if (!IMAGE_REF.matcher(ref).matches()) {
                    continue;
                }
                String name = ref.substring(0, ref.indexOf(':'));
                if (NOT_A_TRACKED_IMAGE.contains(name)) {
                    continue;
                }
                matrix.computeIfAbsent(name, k -> new LinkedHashMap<>())
                        .put(site.label(), ref.substring(ref.indexOf(':') + 1));
            }
        }
        return matrix;
    }

    private static String renderMatrix(Map<String, Map<String, String>> matrix) {
        StringBuilder out = new StringBuilder("%n--- container image matrix ---%n".formatted());
        matrix.forEach((name, bySite) -> {
            out.append("  ").append(name).append(":%n".formatted());
            bySite.forEach((s, tag) -> out.append("      %-42s %s%n".formatted(s, tag)));
        });
        return out.toString();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("image-matrix gate cannot read " + file, e);
        }
    }
}
