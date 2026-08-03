package com.flaviooliva.ledger.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.flaviooliva.ledger",
        importOptions = com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class)
class HexagonalRulesTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that()
            .resideInAPackage("..domain..")
            // spec §9.2 v3.4: package-info boundary metadata (@NamedInterface) is not domain logic.
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
            slices().matching("com.flaviooliva.ledger.(*)..").should().beFreeOfCycles();

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
            .resideOutsideOfPackage("..adapter.in.web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.flaviooliva.ledger.api.generated..");

    @ArchTest // §3.1: time and identity arrive through ports
    static final ArchRule domainNeverCallsNowOrRandomUuid = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .callMethod(java.time.Instant.class, "now")
            .orShould()
            .callMethod(java.util.UUID.class, "randomUUID");
}
