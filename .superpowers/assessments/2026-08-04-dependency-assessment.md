# Dependency Assessment — tiny-ledger

Date: 2026-08-04
Scope: `pom.xml` currency, loose-end hygiene, dependency-gate options for a public solo-maintainer repo.
Method: read-only. `./mvnw versions:display-{dependency,plugin,property,parent}-updates` against Maven Central, plus `./mvnw dependency:analyze`.

## Summary

The build is current: Spring Boot BOM 4.1.0 is already the latest stable, and every reported update is a bare GA (no milestones/RCs snuck in). Five test-scope artifacts are behind — ArchUnit 1.3.0→1.5.0, Cucumber 7.20.1→7.34.6 (×3 modules), JUnit Platform Suite 6.0.3→6.1.2 — plus one build plugin, openapi-generator-maven-plugin 7.10.0→7.24.0. `dependency:analyze` shows only the well-known starter-POM/annotation-only false-positive noise, no real problem. No dependency gate exists yet (`.github/dependabot.yml` absent). Recommendation: add Dependabot (maven + github-actions, weekly) — zero-infra, matches CI's existing shape, and closes the one real gap this repo has.

## Currency table

| Artifact | Current | Latest stable | Managed by | Risk of bumping now |
|---|---|---|---|---|
| spring-boot-starter-parent (BOM) | 4.1.0 | 4.1.0 (confirmed latest via `versions:display-parent-updates`) | inline `<parent>` | n/a — already current |
| spring-modulith-bom | 2.1.0 | 2.1.0 (confirmed via property-updates: "referencing the newest available version") | property `spring-modulith.version` | n/a — already current |
| com.tngtech.archunit:archunit-junit5 | 1.3.0 | 1.5.0 | property `archunit.version` | Low. Test-only, minor version bump, architecture-rule test suite is the blast radius. Re-run `verify` after bump. |
| io.cucumber:cucumber-java / cucumber-junit-platform-engine / cucumber-spring | 7.20.1 | 7.34.6 | property `cucumber.version` | Low–Medium. Test-only but a 14-patch jump across a minor series; skim changelog for step-definition/Spring-glue behavior changes before bumping. Re-run the `@standalone` Cucumber suite. |
| org.junit.platform:junit-platform-suite | 6.0.3 | 6.1.2 | inherited from Spring Boot BOM (not pinned in this pom) | None to act on — this is Spring Boot 4.1.0's own managed version; bumping it standalone would fight the BOM. Leave it; it'll move when the BOM does. |
| org.openapitools:openapi-generator-maven-plugin | 7.10.0 | 7.24.0 | property `openapi-generator.version` | Medium. Codegen output (interfaces/models under `com.ffroliva.tinyledger.api.generated.*`) can change shape across 14 minor versions of a generator — diff the generated sources after bumping, don't just trust `verify` green. |
| com.diffplug.spotless:spotless-maven-plugin | 3.9.0 | 3.9.0 (no update reported — see note) | inline in `<plugin><version>` | n/a — already current |
| org.jacoco:jacoco-maven-plugin | 0.8.15 | 0.8.15 (no update reported — see note) | inline in `<plugin><version>` | n/a — already current |
| spring-boot-maven-plugin | (unversioned, inherited from parent) | tracks parent 4.1.0 | parent-managed | n/a |

Note on plugin-updates output: `versions:display-plugin-updates` only listed `openapi-generator-maven-plugin` under "The following plugin updates are available" — the only real signal. It separately printed noise buckets titled "Require Maven X.Y to use the following plugin updates" for jacoco (0.8.15 → 0.6.3.201306030806, → 0.8.2, → 0.8.15) and spotless (→ 3.9.0); these are the plugin's own historical version/min-Maven-requirement bucketing, not upgrade suggestions — the "0.6.3" and "0.8.2" targets are *older* than what's already in the pom, and the 0.8.15/3.9.0 entries just restate the current version under its own Maven-requirement bucket. Not real gaps.

No milestone/RC/alpha/beta versions were suggested anywhere — every reported "latest" above is a bare numeric GA.

## ArchUnit / test-tooling / build-plugin answers (direct)

- **ArchUnit: currently 1.3.0, latest stable is 1.5.0.** Confirmed by `versions:display-dependency-updates` against Maven Central (`com.tngtech.archunit:archunit-junit5 .................. 1.3.0 -> 1.5.0`). `dependency:analyze` also shows the transitively-pulled `archunit:1.4.2` runtime jar and `archunit-junit5-api:1.3.0` in play — mixed patch versions across the ArchUnit module family is normal for a non-latest pin and resolves cleanly once bumped.
- **Cucumber: 7.20.1 → 7.34.6** (all three modules: cucumber-java, cucumber-junit-platform-engine, cucumber-spring).
- **JUnit (Jupiter/Platform):** not pinned by this pom at all — inherited from the Spring Boot 4.1.0 BOM at 6.0.3 for Jupiter/Platform-suite; a newer 6.1.2 exists upstream but isn't ours to bump independently (would only move with the BOM).
- **spotless-maven-plugin:** 3.9.0, already latest stable.
- **openapi-generator-maven-plugin:** 7.10.0 → 7.24.0, real gap.
- **jacoco-maven-plugin:** 0.8.15, already latest stable.
- **surefire/failsafe:** not declared in `pom.xml` at all — no explicit `maven-surefire-plugin`/`maven-failsafe-plugin` entries; both are inherited from `spring-boot-starter-parent` 4.1.0's plugin management, which is itself confirmed current. Nothing to do here.

## Loose ends

1. **`dependency:analyze` findings — read with the known false-positive lens.**
   - *"Used undeclared"* list (spring-beans, spring-modulith-api, spring-web, assertj-core, json-path, spring-test, hamcrest, spring-boot-autoconfigure, spring-boot-test, junit-jupiter-api, tomcat-embed-core, jackson-annotations, slf4j-api, spring-core, spring-boot, spring-context, archunit-junit5-api, junit-platform-suite-api, mockito-core, archunit, spring-modulith-core, jakarta.validation-api): this is the textbook false-positive pattern for Spring Boot starters — code depends on classes re-exported by `spring-boot-starter-web`/`-test`/`spring-modulith-starter-*`/`archunit-junit5` without needing to declare the transitive jar directly. Confirmed `jackson-annotations` specifically: no `com.fasterxml.jackson` import exists anywhere under `src/main/java` (grep returned zero files), so that particular flag is Jackson's own internal cross-jar reference, not app code relying on an undeclared transitive — nothing to fix.
   - *"Unused declared"* list (spring-boot-starter-web, -validation, spring-modulith-starter-core, spring-boot-starter-test, spring-modulith-starter-test, archunit-junit5, cucumber-junit-platform-engine, junit-platform-suite): all starter/meta-POMs or classpath-activation-only test engines (Cucumber's JUnit Platform engine and `junit-platform-suite` are discovered via classpath + annotations, not bytecode references) — the other well-known false-positive class. Nothing to fix.
   - Net: no real unused-or-transitive-reliance problems found. This was a heuristic pass, not exhaustive.

2. **Version pinning is inconsistent between property and inline.** `archunit.version`, `cucumber.version`, `openapi-generator.version` are pulled into `<properties>`; `spotless-maven-plugin` (3.9.0) and `jacoco-maven-plugin` (0.8.15) have their versions hardcoded inline in the `<plugin>` block instead. Not a bug, but worth normalizing to one convention (properties for anything with a real currency story) if this pom grows more plugins — low priority, config-only, cosmetic.

3. **Spring Boot BOM currency:** 4.1.0 is confirmed the latest stable parent version (`versions:display-parent-updates` returned "The parent project is the latest version").

## Gate options for a public, solo-maintainer showcase repo

| Option | Fit here |
|---|---|
| **Dependabot** (`.github/dependabot.yml`, `maven` + `github-actions`, weekly) | Zero infra, native to GitHub (repo is already public GitHub-hosted), opens PRs your existing `ci.yml` (spotless → verify → docs governance) gates automatically. Also covers the `actions/checkout@v4` / `actions/setup-java@v4` pins in the workflow itself, which nothing else in this list touches. |
| **Renovate** | More configurable (grouping, scheduling windows, changelog-in-PR-body, lockfile-adjacent ecosystems) but that configurability is overhead a solo take-home-style maintainer doesn't need yet — it's a second config surface to maintain for capability this repo won't use. |
| **CI stage running `versions:display-dependency-updates`** | Informational-only, no PRs, someone still has to remember to read CI logs and act — for a solo maintainer this reliably turns into "noted, ignored." Weaker than Dependabot for the same or higher effort. |
| **OWASP dependency-check** | Adds real CVE-gating value but needs an NVD API key to avoid crawl-rate throttling/failures in CI (friction for a small project), plus a local vuln DB to maintain. |
| **GitHub Dependabot security alerts** | Free, zero-config once Dependabot is enabled at all (it's the same product surface as the version-update PRs) — CVE coverage without the NVD key friction. |

**Recommendation:** enable `.github/dependabot.yml` for `maven` + `github-actions`, weekly, and turn on GitHub's native Dependabot security alerts (same feature family, no extra config). That's the minimal set giving real update coverage *and* real CVE coverage, with zero added CI runtime and no credentials to manage — appropriate for a solo-maintained public showcase where the existing `ci.yml` already gates every PR. Skip Renovate (config overhead outweighs benefit at this scale), skip the informational CI stage (Dependabot supersedes it), skip OWASP dependency-check unless/until NVD-key management is worth taking on.

## Suggested next actions

1. **Config-only:** add `.github/dependabot.yml` (maven + github-actions ecosystems, weekly) and confirm Dependabot security alerts are enabled in repo settings. No code changes, no re-verification needed beyond confirming the first Dependabot PR opens cleanly.
2. **Bump-and-reverify (low risk):** ArchUnit 1.3.0 → 1.5.0. Bump `archunit.version`, run `./mvnw verify` (architecture-rule tests are the only real exposure).
3. **Bump-and-reverify (low-medium risk):** Cucumber 7.20.1 → 7.34.6 across all three artifacts via `cucumber.version`. Run the `@standalone` Cucumber suite specifically, not just `verify`'s default pass, since step-glue behavior is the risk surface.
4. **Bump-and-reverify (medium risk, needs a diff review):** openapi-generator-maven-plugin 7.10.0 → 7.24.0. After bumping, diff the regenerated sources under `com.ffroliva.tinyledger.api.generated.*` before trusting a green `verify` — 14 minor versions of a code generator can change output shape in ways tests may not catch if the generated interfaces are only used at compile time.
5. **No action:** junit-platform-suite, Spring Boot BOM, Spring Modulith BOM, spotless-maven-plugin, jacoco-maven-plugin, surefire/failsafe — all already current or not independently controllable from this pom.
