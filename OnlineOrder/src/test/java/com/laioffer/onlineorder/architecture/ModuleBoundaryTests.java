package com.laioffer.onlineorder.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The module map from README.md and docs/ARCHITECTURE.md (ADR 1), enforced. A modular monolith
 * is only modular while these hold; without a test the first convenient import erodes it.
 *
 * <pre>
 *   kitchen ──▶ payment ──▶ ordering ──▶ inventory
 *                  │            │            │
 *                  └────────────┴────────────┴──▶ platform (transactions, outbox, live updates)
 *   catalog (controller / service / repository / entity / model): menus, carts, accounts
 * </pre>
 */
@AnalyzeClasses(packages = "com.laioffer.onlineorder", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTests {

    private static final String ROOT = "com.laioffer.onlineorder.";
    private static final String[] CATALOG = {ROOT + "controller..", ROOT + "service..", ROOT + "repository..",
            ROOT + "entity..", ROOT + "model.."};

    @ArchTest
    static final ArchRule modulesHaveNoCycles = slices()
            .matching(ROOT + "(ordering|inventory|payment|kitchen|platform)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule platformKnowsNoBusinessModule = noClasses()
            .that().resideInAPackage(ROOT + "platform..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + "ordering..", ROOT + "inventory..",
                    ROOT + "payment..", ROOT + "kitchen..", ROOT + "controller..", ROOT + "service..")
            .because("platform is infrastructure every module builds on");

    @ArchTest
    static final ArchRule inventoryOnlyUsesPlatform = noClasses()
            .that().resideInAPackage(ROOT + "inventory..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + "ordering..", ROOT + "payment..",
                    ROOT + "kitchen..")
            .because("stock is reserved and released by ordering, never the other way round");

    @ArchTest
    static final ArchRule orderingDoesNotKnowPayments = noClasses()
            .that().resideInAPackage(ROOT + "ordering..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + "payment..", ROOT + "kitchen..")
            .because("ordering announces cancellations as events; payment reacts (ADR 5)");

    @ArchTest
    static final ArchRule nothingDependsOnKitchen = noClasses()
            .that().resideOutsideOfPackage(ROOT + "kitchen..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + "kitchen..")
            .because("kitchen is the top of the graph: it reads orders and the ledger");

    @ArchTest
    static final ArchRule catalogDoesNotReachIntoOrdering = noClasses()
            .that().resideInAnyPackage(CATALOG)
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + "ordering..", ROOT + "payment..",
                    ROOT + "inventory..", ROOT + "kitchen..");

    @ArchTest
    static final ArchRule catalogIsLayered = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Controller").definedBy(ROOT + "controller..")
            .layer("Service").definedBy(ROOT + "service..")
            .layer("Repository").definedBy(ROOT + "repository..")
            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service");
}
