package io.github.ricardoord.opswatch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.ricardoord.opswatch.monitoring.HandMadeHttpClient;
import java.net.URL;
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

    /**
     * Only {@code egress} builds HTTP clients (OW-024): any other client would skip the guarded resolver and the checks
     * of every request, and with them the SSRF protection. The others ask {@code EgressHttpClients} for one.
     */
    static final ArchRule ONLY_EGRESS_BUILDS_HTTP_CLIENTS = noClasses()
            .that()
            .resideOutsideOfPackage("io.github.ricardoord.opswatch.egress..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.apache.hc.client5.http.impl.classic.HttpClients")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.apache.hc.client5.http.impl.classic.HttpClientBuilder")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.apache.hc.client5.http.impl.async.HttpAsyncClients")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.net.http.HttpClient")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.client.RestClient")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.client.RestTemplate")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.reactive.function.client.WebClient")
            .orShould()
            .callMethod(URL.class, "openConnection")
            .because("an HTTP client built outside egress would skip the SSRF protection")
            .allowEmptyShould(true);

    @Test
    void onlyEgressBuildsHttpClients() {
        ONLY_EGRESS_BUILDS_HTTP_CLIENTS.check(PRODUCTION_CLASSES);
    }

    /** The rule would let anything through if it matched nothing: a class of monitoring that builds a client fails it. */
    @Test
    void aClassOutsideEgressThatBuildsAnHttpClientBreaksTheBuild() {
        JavaClasses handMade = new ClassFileImporter().importClasses(HandMadeHttpClient.class);

        assertThatThrownBy(() -> ONLY_EGRESS_BUILDS_HTTP_CLIENTS.check(handMade))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("HttpClients");
    }

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
