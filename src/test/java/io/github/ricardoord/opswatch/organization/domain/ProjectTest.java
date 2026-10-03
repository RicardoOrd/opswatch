package io.github.ricardoord.opswatch.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProjectTest {

    private static final Clock CREATED = Clock.fixed(Instant.parse("2026-10-02T10:00:00.123456789Z"), ZoneOffset.UTC);
    private static final Clock LATER = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID ORGANIZATION = UUID.randomUUID();
    /** RIGHT-TO-LEFT OVERRIDE, built from its code point so that the source never holds the invisible character. */
    private static final String BIDI_OVERRIDE = Character.toString(0x202E);

    @Test
    void createsWithoutSurroundingSpacesAndBelongsToItsOrganization() {
        Project project = Project.create(UUID.randomUUID(), ORGANIZATION, "  Production ", " Live ", CREATED);

        assertThat(project.organizationId()).isEqualTo(ORGANIZATION);
        assertThat(project.name()).isEqualTo("Production");
        assertThat(project.description()).isEqualTo("Live");
        assertThat(project.createdAt()).isEqualTo(Instant.parse("2026-10-02T10:00:00.123456Z"));
        assertThat(project.updatedAt()).isEqualTo(project.createdAt());
        assertThat(project.isDeleted()).isFalse();
    }

    @Test
    void aBlankDescriptionIsNone() {
        assertThat(Project.create(UUID.randomUUID(), ORGANIZATION, "Production", "   ", CREATED)
                        .description())
                .isNull();
        assertThat(Project.create(UUID.randomUUID(), ORGANIZATION, "Production", null, CREATED)
                        .description())
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "Pro\nduction", "Pro\tduction"})
    void rejectsBlankNamesAndControlCharacters(String name) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Project.create(UUID.randomUUID(), ORGANIZATION, name, null, CREATED));
    }

    @Test
    void rejectsBidirectionalOverridesInTheNameAndTheDescription() {
        String disguised = "Pro" + BIDI_OVERRIDE + "duction";

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Project.create(UUID.randomUUID(), ORGANIZATION, disguised, null, CREATED));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Project.create(UUID.randomUUID(), ORGANIZATION, "Production", disguised, CREATED));
    }

    @Test
    void acceptsNamesOfUpTo100CharactersAndDescriptionsOfUpTo500() {
        assertThat(Project.create(UUID.randomUUID(), ORGANIZATION, "a".repeat(100), "d".repeat(500), CREATED)
                        .name())
                .hasSize(100);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Project.create(UUID.randomUUID(), ORGANIZATION, "a".repeat(101), null, CREATED));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> Project.create(UUID.randomUUID(), ORGANIZATION, "Production", "d".repeat(501), CREATED));
    }

    @Test
    void countsCharactersNotUtf16Units() {
        String emoji = Character.toString(0x1F680);

        assertThat(Project.create(UUID.randomUUID(), ORGANIZATION, emoji.repeat(100), null, CREATED)
                        .name())
                .hasSize(200);
    }

    @Test
    void renamesAndDescribesTouchingUpdatedAtOnlyOnARealChange() {
        Project project = Project.create(UUID.randomUUID(), ORGANIZATION, "Production", "Live", CREATED);

        project.rename("Production", LATER);
        project.describe("Live", LATER);
        assertThat(project.updatedAt()).isEqualTo(project.createdAt());

        project.rename(" Staging ", LATER);
        assertThat(project.name()).isEqualTo("Staging");
        assertThat(project.updatedAt()).isEqualTo(LATER.instant());

        project.describe(null, LATER);
        assertThat(project.description()).isNull();
    }

    @Test
    void deletingTwiceKeepsTheFirstTime() {
        Project project = Project.create(UUID.randomUUID(), ORGANIZATION, "Production", null, CREATED);

        project.delete(CREATED);
        project.delete(LATER);

        assertThat(project.isDeleted()).isTrue();
        assertThat(project.updatedAt()).isEqualTo(Instant.parse("2026-10-02T10:00:00.123456Z"));
    }
}
