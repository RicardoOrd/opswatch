package io.github.ricardoord.opswatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernateProperties;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;

/** The whole application on real ports against PostgreSQL: what a deployment would run, minus the profile. */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        // Without the test profile the engine would run, and keep checking every monitor due in the shared database
        // for as long as this context stays cached, with the real DNS and the real client (OW-030). The same goes for
        // the deliveries of the shared database, and the worker needs an SMTP server (OW-036)
        properties = {
            "opswatch.monitoring.engine.enabled=false",
            "opswatch.notification.delivery.enabled=false",
            // An SMTP server that is not there: the application must stay healthy and ready without it
            "spring.mail.host=127.0.0.1",
            "spring.mail.port=1"
        })
@Import({PostgresTestcontainer.class, TestJwtKeys.class, TestEncryptionKeys.class})
@ExtendWith(OutputCaptureExtension.class)
class ApplicationStartupIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private Flyway flyway;

    @Autowired
    private HibernateProperties hibernateProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LoggingSystem loggingSystem;

    @Test
    void connectsToPostgres18() {
        Integer serverVersion = jdbcTemplate.queryForObject("SHOW server_version_num", Integer.class);

        assertThat(serverVersion).isBetween(180000, 189999);
    }

    @Test
    void appliesMigrationsWithFlywayAndOnlyValidatesTheSchemaWithHibernate() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(hibernateProperties.getDdlAuto()).isEqualTo("validate");
    }

    @Test
    void refusesToCleanTheDatabase() {
        assertThatThrownBy(flyway::clean).isInstanceOf(FlywayException.class).hasMessageContaining("disabled");
    }

    @Test
    void reportsReadinessOnTheManagementPort() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/health/readiness", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    /** Acceptance criterion of OW-036: the deliveries retry a server that is down, so it never takes traffic away. */
    @Test
    void staysHealthyAndReadyWithTheSmtpServerDown() throws Exception {
        HttpResponse<String> health = get(managementPort, "/actuator/health", null);

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        assertThat(get(managementPort, "/actuator/health/readiness", null).statusCode())
                .isEqualTo(200);
    }

    @Test
    void exposesNoActuatorEndpointBeyondHealthInfoAndPrometheus() throws Exception {
        assertThat(get(managementPort, "/actuator/env", null).statusCode()).isEqualTo(404);
    }

    /** On the management port, which is never published; on the port of the API there is no actuator at all. */
    @Test
    void servesPrometheusOnlyOnTheManagementPort() throws Exception {
        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus", null);

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("opswatch_monitor_checks_overdue").contains("jvm_memory_used_bytes");
        assertThat(get(serverPort, "/actuator/prometheus", null).statusCode()).isNotEqualTo(200);
    }

    @Test
    void logsEachRequestWithItsRequestId(CapturedOutput output) throws Exception {
        String logger = FilterChainProxy.class.getName();
        loggingSystem.setLogLevel(logger, LogLevel.DEBUG);
        try {
            HttpResponse<String> response = get(serverPort, "/api/v1/anything", "startup-it-request");

            assertThat(response.statusCode()).isEqualTo(401);
        } finally {
            loggingSystem.setLogLevel(logger, null);
        }

        assertThat(output).containsPattern("\\[startup-it-request\\] [^\\n]*Securing GET /api/v1/anything");
    }

    private HttpResponse<String> get(int port, String path, @Nullable String requestId)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (requestId != null) {
            request.header("X-Request-Id", requestId);
        }
        return HTTP.send(request.build(), BodyHandlers.ofString());
    }
}
