package io.github.ricardoord.opswatch.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.incident.IncidentSummary;
import io.github.ricardoord.opswatch.incident.Resolution;
import io.github.ricardoord.opswatch.notification.domain.DeliveryEventType;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EmailTemplatesTest {

    private static final Instant OPENED_AT = Instant.parse("2026-09-28T10:03:00Z");
    private static final UUID INCIDENT = UUID.fromString("01926b3a-0000-7000-8000-000000000001");

    private final EmailTemplates templates = new EmailTemplates();

    @Test
    void theOpeningSaysWhichMonitorIsDownSinceWhenAndWhy() {
        EmailTemplates.EmailContent email =
                templates.render(notice(DeliveryEventType.INCIDENT_OPENED, incident("Payments API", 503, null, null)));

        assertThat(email.subject()).isEqualTo("[OpsWatch] DOWN: Payments API");
        assertThat(email.text())
                .contains("DOWN: Payments API")
                .contains("Since:       2026-09-28 10:03:00 UTC")
                .contains("Cause:       UNEXPECTED_STATUS")
                .contains("HTTP status: 503")
                .contains("Incident:    " + INCIDENT)
                .contains("the channel On call");
        assertThat(email.html())
                .contains("<strong>Payments API</strong>")
                .contains("2026-09-28 10:03:00 UTC")
                .contains("<td>503</td>");
    }

    @Test
    void anOpeningWithoutAResponseHasNoHttpStatus() {
        EmailTemplates.EmailContent email =
                templates.render(notice(DeliveryEventType.INCIDENT_OPENED, incident("Payments API", null, null, null)));

        assertThat(email.text()).doesNotContain("HTTP status");
        assertThat(email.html()).doesNotContain("HTTP status");
    }

    @Test
    void theResolutionSaysWhyAndHowLongItLasted() {
        EmailTemplates.EmailContent email = templates.render(notice(
                DeliveryEventType.INCIDENT_RESOLVED,
                incident("Payments API", 503, OPENED_AT.plusSeconds(603), Resolution.MONITOR_PAUSED)));

        assertThat(email.subject()).isEqualTo("[OpsWatch] RESOLVED: Payments API");
        assertThat(email.text())
                .contains("Resolved because the monitor was paused.")
                .contains("Resolved at: 2026-09-28 10:13:03 UTC")
                .contains("Duration:    10 min 3 s");
        assertThat(email.html()).contains("the monitor was paused").contains("10 min 3 s");
    }

    @Test
    void theTestNamesTheChannel() {
        EmailTemplates.EmailContent email = templates.render(notice(DeliveryEventType.TEST, null));

        assertThat(email.subject()).isEqualTo("[OpsWatch] Test notification: On call");
        assertThat(email.text()).contains("test the channel On call");
        assertThat(email.html()).contains("<strong>On call</strong>");
    }

    /** T-34: a monitor name is typed by a user, and a subject is a header. */
    @Test
    void aMonitorNameWithHtmlAndLineBreaksIsEscapedInTheBodyAndKeptOnOneLineInTheSubject() {
        String name = "<img src=x onerror=alert(1)>\r\nBcc: victim@example.com";

        EmailTemplates.EmailContent email =
                templates.render(notice(DeliveryEventType.INCIDENT_OPENED, incident(name, null, null, null)));

        assertThat(email.subject())
                .isEqualTo("[OpsWatch] DOWN: <img src=x onerror=alert(1)> Bcc: victim@example.com")
                .doesNotContain("\r")
                .doesNotContain("\n");
        assertThat(email.html()).doesNotContain("<img").contains("&lt;img src=x onerror=alert(1)&gt;");
    }

    @ParameterizedTest
    @CsvSource({"0, 0 s", "45, 45 s", "603, 10 min 3 s", "7500, 2 h 5 min", "273600, 3 d 4 h"})
    void durationsShowTheTwoLargestUnits(long seconds, String expected) {
        assertThat(EmailTemplates.duration(Duration.ofSeconds(seconds))).isEqualTo(expected);
    }

    private static Notice notice(DeliveryEventType type, @Nullable IncidentSummary incident) {
        return new Notice(UUID.randomUUID(), type, OPENED_AT, "On call", incident);
    }

    private static IncidentSummary incident(
            String monitorName,
            @Nullable Integer httpStatus,
            @Nullable Instant resolvedAt,
            @Nullable Resolution resolution) {
        return new IncidentSummary(
                INCIDENT,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                monitorName,
                "UNEXPECTED_STATUS",
                httpStatus,
                OPENED_AT,
                resolvedAt,
                resolution);
    }
}
