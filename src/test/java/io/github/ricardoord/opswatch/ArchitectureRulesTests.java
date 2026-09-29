package io.github.ricardoord.opswatch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * Coding rules that Spring Modulith does not cover. See docs/testing/testing-strategy.md#arquitectura. Rules allow
 * empty sets because most packages have no classes yet during Sprint 0.
 */
class ArchitectureRulesTests {

    private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.ricardoord.opswatch");

    @Test
    void noFieldInjection() {
        NO_CLASSES_SHOULD_USE_FIELD_INJECTION.allowEmptyShould(true).check(PRODUCTION_CLASSES);
    }

    @Test
    void timeComesFromTheInjectedClock() {
        noClasses()
                .should()
                .callMethod(Instant.class, "now")
                .orShould()
                .callMethod(LocalDateTime.class, "now")
                .orShould()
                .callMethod(LocalDate.class, "now")
                .orShould()
                .callMethod(OffsetDateTime.class, "now")
                .orShould()
                .callMethod(ZonedDateTime.class, "now")
                .orShould()
                .callMethod(System.class, "currentTimeMillis")
                .because("the current time must come from an injected java.time.Clock so tests can control it")
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void webLayerDoesNotOpenTransactions() {
        noClasses()
                .that()
                .resideInAPackage("..web..")
                .should()
                .beAnnotatedWith(SPRING_TRANSACTIONAL)
                .orShould()
                .beAnnotatedWith(JAKARTA_TRANSACTIONAL)
                .because("transactions belong to the application layer")
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);

        noMethods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage("..web..")
                .should()
                .beAnnotatedWith(SPRING_TRANSACTIONAL)
                .orShould()
                .beAnnotatedWith(JAKARTA_TRANSACTIONAL)
                .because("transactions belong to the application layer")
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }
}
