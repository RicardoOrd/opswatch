package io.github.ricardoord.opswatch.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrganizationTest {

    private static final Clock CREATED = Clock.fixed(Instant.parse("2026-09-29T10:00:00.123456789Z"), ZoneOffset.UTC);
    private static final Clock LATER = Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void createsWithTheNameWithoutSurroundingSpaces() {
        Organization organization = Organization.create(UUID.randomUUID(), "  CharityLink ", CREATED);

        assertThat(organization.name()).isEqualTo("CharityLink");
        assertThat(organization.createdAt()).isEqualTo(Instant.parse("2026-09-29T10:00:00.123456Z"));
        assertThat(organization.isDeleted()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "Charity\nLink", "Charity\tLink"})
    void rejectsBlankNamesAndControlCharacters(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> Organization.create(UUID.randomUUID(), name, CREATED));
    }

    @Test
    void acceptsNamesOfUpTo100Characters() {
        assertThat(Organization.create(UUID.randomUUID(), "a".repeat(100), CREATED)
                        .name())
                .hasSize(100);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Organization.create(UUID.randomUUID(), "a".repeat(101), CREATED));
    }

    @Test
    void renames() {
        Organization organization = Organization.create(UUID.randomUUID(), "CharityLink", CREATED);

        organization.rename(" Charity Link ", LATER);

        assertThat(organization.name()).isEqualTo("Charity Link");
        assertThatIllegalArgumentException().isThrownBy(() -> organization.rename(" ", LATER));
        assertThat(organization.name()).isEqualTo("Charity Link");
    }

    @Test
    void deletingTwiceKeepsTheFirstDeletion() {
        Organization organization = Organization.create(UUID.randomUUID(), "CharityLink", CREATED);

        organization.delete(CREATED);
        organization.delete(LATER);

        assertThat(organization.isDeleted()).isTrue();
    }

    @Test
    void hasNoVersionUntilSaved() {
        Organization organization = Organization.create(UUID.randomUUID(), "CharityLink", CREATED);

        assertThatNullPointerException().isThrownBy(organization::savedVersion);
    }
}
