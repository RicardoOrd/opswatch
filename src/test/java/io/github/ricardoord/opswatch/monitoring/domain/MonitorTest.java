package io.github.ricardoord.opswatch.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.ricardoord.opswatch.organization.ProjectRef;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MonitorTest {

    private static final Clock CREATED = Clock.fixed(Instant.parse("2026-10-03T10:00:00.123456789Z"), ZoneOffset.UTC);
    private static final Clock LATER = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC);
    private static final ProjectRef PROJECT = new ProjectRef(UUID.randomUUID(), UUID.randomUUID());
    private static final URI URL = URI.create("https://api.example.com/health?token=s3cr3t");
    private static final UUID CREATOR = UUID.randomUUID();
    /** RIGHT-TO-LEFT OVERRIDE, built from its code point so that the source never holds the invisible character. */
    private static final String BIDI_OVERRIDE = Character.toString(0x202E);

    @Test
    void takesItsProjectAndOrganizationFromTheProjectAndStripsTheName() {
        Monitor monitor = monitor("  Payments API ");

        assertThat(monitor.projectId()).isEqualTo(PROJECT.id());
        assertThat(monitor.organizationId()).isEqualTo(PROJECT.organizationId());
        assertThat(monitor.name()).isEqualTo("Payments API");
        assertThat(monitor.url()).isEqualTo(URL);
        assertThat(monitor.settings()).isEqualTo(MonitorSettings.DEFAULTS);
        assertThat(monitor.createdBy()).isEqualTo(CREATOR);
        assertThat(monitor.createdAt()).isEqualTo(Instant.parse("2026-10-03T10:00:00.123456Z"));
        assertThat(monitor.updatedAt()).isEqualTo(monitor.createdAt());
        assertThat(monitor.isDeleted()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "Pay\nments", "Pay\tments"})
    void rejectsBlankNamesAndControlCharacters(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> monitor(name));
    }

    @Test
    void rejectsBidirectionalOverridesInTheName() {
        assertThatIllegalArgumentException().isThrownBy(() -> monitor("Pay" + BIDI_OVERRIDE + "ments"));
    }

    /** Characters, not UTF-16 units: an emoji counts once. */
    @Test
    void measuresTheNameInCharacters() {
        String emoji = Character.toString(0x1F680);

        assertThat(monitor(emoji.repeat(100)).name()).hasSize(200);
        assertThatIllegalArgumentException().isThrownBy(() -> monitor(emoji.repeat(101)));
    }

    @Test
    void rejectsAUrlLongerThanTheColumn() {
        URI tooLong = URI.create("https://api.example.com/" + "a".repeat(2048));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Monitor.create(
                        UUID.randomUUID(),
                        PROJECT,
                        "Payments",
                        tooLong,
                        MonitorSettings.DEFAULTS,
                        null,
                        CREATOR,
                        CREATED));
    }

    @Test
    void onlyARealChangeTouchesUpdatedAt() {
        Monitor monitor = monitor("Payments API");

        monitor.rename(" Payments API ", LATER);
        monitor.retarget(URL, LATER);
        monitor.reconfigure(MonitorSettings.DEFAULTS, LATER);
        assertThat(monitor.updatedAt()).isEqualTo(monitor.createdAt());

        MonitorSettings faster = new MonitorSettings(ProbeMethod.HEAD, 200, 204, 30, 5000, 2000, false, 1, 1);
        monitor.reconfigure(faster, LATER);
        assertThat(monitor.settings()).isEqualTo(faster);
        assertThat(monitor.updatedAt()).isEqualTo(LATER.instant());
    }

    @Test
    void retargetsAndRenames() {
        Monitor monitor = monitor("Payments API");

        monitor.rename("Donations API", LATER);
        monitor.retarget(URI.create("https://donations.example.com/health"), LATER);

        assertThat(monitor.name()).isEqualTo("Donations API");
        assertThat(monitor.url()).hasToString("https://donations.example.com/health");
        assertThat(monitor.updatedAt()).isEqualTo(LATER.instant());
    }

    /** The sealed headers are kept as they come: nobody outside can change them through a shared array. */
    @Test
    void replacesItsSealedHeadersAndOnlyHandsOutCopies() {
        Monitor monitor = monitor("Payments API");
        byte[] sealed = {1, 2, 3};

        monitor.replaceHeaders(sealed, LATER);
        sealed[0] = 9;
        byte[] read = monitor.requestHeaders();
        assertThat(read).containsExactly(1, 2, 3);
        read[1] = 9;

        assertThat(monitor.requestHeaders()).containsExactly(1, 2, 3);
        assertThat(monitor.updatedAt()).isEqualTo(LATER.instant());
        monitor.replaceHeaders(null, LATER);
        assertThat(monitor.requestHeaders()).isNull();
    }

    @Test
    void aDeletionKeepsItsFirstMoment() {
        Monitor monitor = monitor("Payments API");

        monitor.delete(LATER);
        monitor.delete(Clock.offset(LATER, Duration.ofHours(1)));

        assertThat(monitor.isDeleted()).isTrue();
        assertThat(monitor.updatedAt()).isEqualTo(LATER.instant());
    }

    /** The query string of a URL may carry a token. */
    @Test
    void neverPrintsItsUrl() {
        assertThat(monitor("Payments API").toString()).doesNotContain("s3cr3t").doesNotContain("example");
    }

    private static Monitor monitor(String name) {
        return Monitor.create(UUID.randomUUID(), PROJECT, name, URL, MonitorSettings.DEFAULTS, null, CREATOR, CREATED);
    }
}
