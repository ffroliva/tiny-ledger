package com.ffroliva.tinyledger.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The pipeline, fenced the way {@link HexagonalRulesTest} fences the packages.
 *
 * <p>{@code ci.yml} is 1031 lines that run eleven jobs, hold {@code SONAR_TOKEN} and
 * {@code NVD_API_KEY}, and call five third-party actions — and until this test it was the one
 * surface here with no gate behind it. It had drifted accordingly: no {@code permissions:} block at
 * all, so every job ran on the repository's default {@code GITHUB_TOKEN} scopes; one
 * {@code timeout-minutes} across eleven jobs, leaving GitHub's six-hour default on the other ten;
 * and every third-party action floating on a tag its owner can repoint between two runs.
 *
 * <p><b>Why this parses lines rather than YAML.</b> No YAML parser is on this project's test
 * classpath, and adding a dependency to lint four structural shapes is not a trade worth making.
 * The shapes are anchored to column: jobs at two spaces, job keys at four.
 *
 * <p><b>Why the fixtures below exist (AGENTS.md trap 8).</b> A checker asserted only against a tree
 * that already passes is an observation, not a check — it scores the same whether the parser works
 * or is inert. So {@link #violations} runs over synthetic workflows that <em>must</em> score hits
 * and one that <em>must</em> score zero, with the identical code path the real files take. That is
 * the differential; the green run on {@code .github/workflows} is only half of it.
 */
class WorkflowGovernanceTest {

    private static final Path WORKFLOW_DIR = Path.of(".github", "workflows");

    /** Anything shorter than a full commit SHA — a tag, a branch, an abbreviation — is mutable. */
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    /**
     * {@code actions/*} is GitHub-owned and shares GitHub's own compromise surface, so pinning it
     * buys nothing a floating tag does not already give. Every other owner runs arbitrary code
     * against this repository's token.
     */
    private static final Set<String> FIRST_PARTY_OWNERS = Set.of("actions");

    /**
     * {@code depcheck}'s cold NVD run measured 70 minutes (run 31239298941), which sets this floor.
     * Still three times under GitHub's 360-minute default, which is the number worth bounding: it
     * turns one hung step into a six-hour bill and a pull request nobody can merge.
     */
    private static final int MAX_TIMEOUT_MINUTES = 120;

    /**
     * The one job allowed to swallow a failing step, and the reason is in {@code ci.yml} beside it:
     * Gatling's assertions failing IS the run whose HTML report and application log someone needs,
     * and a hard failure there would skip the upload steps that carry them. Every other
     * {@code continue-on-error} is a gate that cannot fail, which is not a gate — this repository
     * has already shipped two of those (see the Trivy {@code exit-code} comment in {@code ci.yml}).
     */
    private static final Set<String> JOBS_ALLOWED_TO_CONTINUE_ON_ERROR = Set.of("load");

    // ---------------------------------------------------------------------------------------
    // The rules, applied to the real pipeline.
    // ---------------------------------------------------------------------------------------

    @Test
    void thereAreWorkflowsToCheck() {
        // Trap 1's shape: every other assertion here passes vacuously against an empty directory,
        // so a mistyped path would report green while enforcing nothing.
        assertThat(workflowFiles()).as("workflow files under %s", WORKFLOW_DIR).isNotEmpty();
    }

    @Test
    void everyWorkflowSatisfiesEveryRule() {
        List<String> found = new ArrayList<>();
        for (Path file : workflowFiles()) {
            found.addAll(violations(file.getFileName().toString(), readLines(file)));
        }
        assertThat(found).as("pipeline governance violations").isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // The differential: the identical checker over cases that must fail, and one that must not.
    // ---------------------------------------------------------------------------------------

    @Test
    void theCheckerRejectsAWorkflowWithNoPermissionsBlock() {
        String workflow = """
                name: bad
                on: push
                jobs:
                  broken:
                    runs-on: ubuntu-latest
                    timeout-minutes: 5
                    steps:
                      - uses: actions/checkout@v7
                """;

        assertThat(violations("bad.yml", workflow.lines().toList()))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("no top-level `permissions:` block");
    }

    @Test
    void theCheckerRejectsWriteScopesAtTheTopLevel() {
        // A top-level `write` applies to every job in the file. Escalation belongs at the one job
        // that needs it — which is why the vocabulary here is read-only rather than merely present.
        String workflow = """
                name: bad
                on: push
                permissions:
                  contents: read
                  packages: write
                jobs:
                  broken:
                    runs-on: ubuntu-latest
                    timeout-minutes: 5
                    steps:
                      - uses: actions/checkout@v7
                """;

        assertThat(violations("bad.yml", workflow.lines().toList()))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("packages")
                .contains("write");
    }

    @Test
    void theCheckerRejectsMissingTimeoutsFloatingActionsAndSilentFailure() {
        String workflow = """
                name: bad
                on: push
                permissions:
                  contents: read
                jobs:
                  broken:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v7
                      - uses: evilcorp/rootkit-action@v1
                        continue-on-error: true
                """;

        assertThat(violations("bad.yml", workflow.lines().toList()))
                .hasSize(3)
                .anySatisfy(v -> assertThat(v).contains("timeout-minutes"))
                .anySatisfy(v -> assertThat(v).contains("evilcorp/rootkit-action@v1"))
                .anySatisfy(v -> assertThat(v).contains("continue-on-error"));
    }

    @Test
    void theCheckerPassesAWorkflowThatSatisfiesEveryRule() {
        // The must-score-zero half. Without it, a checker that returned a violation for every input
        // would pass all three cases above and still be worthless.
        String workflow = """
                name: good
                on: push
                permissions:
                  contents: read
                  actions: none
                jobs:
                  fine:
                    runs-on: ubuntu-latest
                    timeout-minutes: 15   # a trailing comment is valid YAML and must still parse
                    steps:
                      - uses: actions/checkout@v7
                      - uses: evilcorp/rootkit-action@1111111111111111111111111111111111111111
                """;

        assertThat(violations("good.yml", workflow.lines().toList())).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // The checker itself.
    // ---------------------------------------------------------------------------------------

    private static final Pattern JOB_HEADER = Pattern.compile("^ {2}([A-Za-z0-9_-]+):\\s*$");
    // The trailing `(#.*)?` is not decoration: without it this pattern rejected
    // `timeout-minutes: 20   # measured 1m`, a perfectly valid YAML scalar with a comment, and
    // reported seven jobs as untimed while they were sitting right there in the file.
    private static final Pattern JOB_TIMEOUT = Pattern.compile("^ {4}timeout-minutes:\\s*(\\d+)\\s*(#.*)?$");
    private static final Pattern USES = Pattern.compile("^\\s*-?\\s*uses:\\s*(\\S+)");
    private static final Pattern CONTINUES_ON_ERROR = Pattern.compile("^\\s*continue-on-error:\\s*true\\s*$");
    private static final Pattern PERMISSION = Pattern.compile("^ +([a-z][a-z-]*):\\s*['\"]?([a-z-]+)['\"]?\\s*(#.*)?$");

    /** Every rule, over one workflow's lines. Empty means the file is compliant. */
    private static List<String> violations(String name, List<String> lines) {
        List<String> found = new ArrayList<>();
        found.addAll(permissionViolations(name, lines));
        for (Job job : jobs(lines)) {
            found.addAll(jobViolations(name, job));
        }
        return found;
    }

    private static List<String> permissionViolations(String name, List<String> lines) {
        int header = lines.indexOf("permissions:");
        if (header < 0) {
            // Deliberately also catches the inline forms — `permissions: write-all`, `read-all`,
            // `{}` — none of which equal the bare header this looks for.
            return List.of(name + ": no top-level `permissions:` block");
        }
        List<String> found = new ArrayList<>();
        for (String line : lines.subList(header + 1, lines.size())) {
            if (line.isBlank() || line.strip().startsWith("#")) continue;
            if (!line.startsWith(" ")) break; // the next column-0 key ends the block
            Matcher entry = PERMISSION.matcher(line);
            if (!entry.matches()) {
                found.add(name + ": unparseable line under `permissions:`: " + line.strip());
            } else if (!entry.group(2).equals("read") && !entry.group(2).equals("none")) {
                found.add(name + ": top-level scope `" + entry.group(1) + "` grants `" + entry.group(2)
                        + "`; top-level permissions must be read-only, escalate at the job that needs it");
            }
        }
        return found;
    }

    private static List<String> jobViolations(String name, Job job) {
        List<String> found = new ArrayList<>();
        String where = name + ": job `" + job.name() + "`";

        job.body().stream()
                .map(JOB_TIMEOUT::matcher)
                .filter(Matcher::matches)
                .map(m -> Integer.parseInt(m.group(1)))
                .findFirst()
                .ifPresentOrElse(
                        minutes -> {
                            if (minutes < 1 || minutes > MAX_TIMEOUT_MINUTES) {
                                found.add(where + " sets timeout-minutes " + minutes + ", outside 1.."
                                        + MAX_TIMEOUT_MINUTES);
                            }
                        },
                        () -> found.add(where + " declares no `timeout-minutes`; GitHub's default is 360"));

        for (String line : job.body()) {
            Matcher uses = USES.matcher(line);
            if (uses.find()) {
                String ref = uses.group(1);
                int at = ref.lastIndexOf('@');
                String owner = ref.split("/", 2)[0];
                if (at >= 0
                        && !FIRST_PARTY_OWNERS.contains(owner)
                        && !SHA.matcher(ref.substring(at + 1)).matches()) {
                    found.add(where + " uses third-party action " + ref
                            + " on a mutable ref; pin the 40-character commit SHA");
                }
            }
            if (CONTINUES_ON_ERROR.matcher(line).matches() && !JOBS_ALLOWED_TO_CONTINUE_ON_ERROR.contains(job.name())) {
                found.add(where + " sets `continue-on-error: true`; a gate that cannot fail is not a gate");
            }
        }
        return found;
    }

    private record Job(String name, List<String> body) {}

    /**
     * Jobs, read only from under the column-0 {@code jobs:} key. Scanning the whole file for the
     * two-space header shape instead would also match {@code push:}, {@code pull_request:} and
     * {@code workflow_dispatch:} under {@code on:} — three phantom jobs, each reported as missing a
     * timeout it could never carry.
     */
    private static List<Job> jobs(List<String> lines) {
        int start = lines.indexOf("jobs:");
        if (start < 0) return List.of();

        List<Job> found = new ArrayList<>();
        Job current = null;
        for (String line : lines.subList(start + 1, lines.size())) {
            if (!line.isBlank() && !line.startsWith(" ")) break;
            Matcher header = JOB_HEADER.matcher(line);
            if (header.matches()) {
                current = new Job(header.group(1), new ArrayList<>());
                found.add(current);
            } else if (current != null) {
                current.body().add(line);
            }
        }
        return found;
    }

    private static List<Path> workflowFiles() {
        try (Stream<Path> files = Files.list(WORKFLOW_DIR)) {
            return files.filter(
                            p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + WORKFLOW_DIR.toAbsolutePath(), e);
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
