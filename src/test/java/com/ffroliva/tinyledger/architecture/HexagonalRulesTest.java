package com.ffroliva.tinyledger.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.ffroliva.tinyledger",
        importOptions = com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class)
class HexagonalRulesTest {

    /**
     * {@code ..shared..} is fenced alongside {@code ..domain..}, and that is load-bearing rather than
     * thorough. ArchUnit checks <em>direct</em> dependencies, so with {@code ..domain..} alone a Spring import
     * in {@code shared.error.ErrorCode} stayed green while landing on the domain's transitive compile path —
     * and Task 1 made {@code Account} import {@code shared.error.InvalidAmountException}, so the domain now
     * genuinely compiles against {@code shared}. {@code ErrorCode}'s own javadoc already stated the rule;
     * nothing enforced it, which AGENTS.md counts as a defect. Proven by violation: a temporary
     * {@code org.springframework} import in {@code ErrorCode} fails this rule.
     */
    @ArchTest
    static final ArchRule domainAndSharedAreFrameworkFree = noClasses()
            .that()
            .resideInAnyPackage("..domain..", "..shared..")
            // spec §9.2 v3.4: package-info boundary metadata (@NamedInterface, @ApplicationModule) is not
            // domain logic — `shared`'s package-info carries the Modulith OPEN marker.
            .and()
            .doNotHaveSimpleName("package-info")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.apache.kafka..", "io.lettuce..");

    @ArchTest
    static final ArchRule applicationCarriesNoSpringAnnotations = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .beAnnotatedWith("org.springframework.stereotype.Service")
            .orShould()
            .beAnnotatedWith("org.springframework.stereotype.Component")
            .orShould()
            .beAnnotatedWith("org.springframework.transaction.annotation.Transactional");

    @ArchTest
    static final ArchRule adaptersNeverCallAdapters =
            slices().matching("..adapter.out.(*)..").should().notDependOnEachOther();

    @ArchTest
    static final ArchRule onlyConfigInstantiatesOutboundAdapters = noClasses()
            .that()
            .resideOutsideOfPackages("..config..", "..adapter.out..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter.out..");

    @ArchTest
    static final ArchRule noCyclicPackages =
            slices().matching("com.ffroliva.tinyledger.(*)..").should().beFreeOfCycles();

    @ArchTest // §9.2 anti-CRUD: one use case, one service
    static final ArchRule noServiceDependsOnAnotherService = noClasses()
            .that()
            .resideInAPackage("..application.usecase..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..application.usecase..");

    @ArchTest // §4.6: wire DTOs only below the web adapter
    static final ArchRule generatedDtosStayInWebAdapters = noClasses()
            .that()
            // the generated sources reference each other (Api → model); the rule fences hand-written code.
            .resideOutsideOfPackages("..adapter.in.web..", "com.ffroliva.tinyledger.api.generated..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.ffroliva.tinyledger.api.generated..");

    @ArchTest // §3/§4.5: composition lives in one place. `config` is the composition root and the only
    // place the two run modes are assembled (§1); `platform` holds the two framework-level guards
    // (ErrorHandlingAdvice, FailClosedGuard). A @Configuration inside a business module would make
    // Spring wiring part of a closed Modulith module and scatter the profile story across the codebase,
    // so that "what does `full` wire?" stops having a single answer.
    static final ArchRule onlyCompositionPackagesDeclareConfiguration = noClasses()
            .that()
            .resideOutsideOfPackages("..config..", "..platform..")
            .should()
            .beAnnotatedWith("org.springframework.context.annotation.Configuration");

    @ArchTest // §3.1: time and identity arrive through ports
    static final ArchRule domainNeverCallsNowOrRandomUuid = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .callMethod(java.time.Instant.class, "now")
            .orShould()
            .callMethod(java.util.UUID.class, "randomUUID");
}
