package io.github.ricardoord.opswatch.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.exc.InvalidNullException;
import tools.jackson.databind.json.JsonMapper;

class NotNullIfPresentTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    record Patch(
            @JsonDeserialize(using = NotNullIfPresent.class) @Nullable
            String name,

            @Nullable Integer size) {}

    @Test
    void anAbsentFieldStaysNull() {
        assertThat(mapper.readValue("{}", Patch.class)).isEqualTo(new Patch(null, null));
    }

    @Test
    void aPresentValueIsReadAsUsual() {
        assertThat(mapper.readValue("{\"name\": \"Ana\", \"size\": 3}", Patch.class))
                .isEqualTo(new Patch("Ana", 3));
    }

    @Test
    void anExplicitNullIsRejected() {
        assertThatThrownBy(() -> mapper.readValue("{\"name\": null}", Patch.class))
                .isInstanceOf(InvalidNullException.class)
                .hasMessageContaining("name");
    }

    @Test
    void otherFieldsStillAcceptNull() {
        assertThat(mapper.readValue("{\"size\": null}", Patch.class)).isEqualTo(new Patch(null, null));
    }
}
