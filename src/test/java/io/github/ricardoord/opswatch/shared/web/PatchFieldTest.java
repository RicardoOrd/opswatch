package io.github.ricardoord.opswatch.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PatchFieldTest {

    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Validator validator = VALIDATION.getValidator();

    record Patch(PatchField<@Size(max = 3) String> note, PatchField<Integer> size) {}

    @AfterAll
    static void close() {
        VALIDATION.close();
    }

    @Test
    void anAbsentFieldIsAbsentNotNull() {
        Patch patch = mapper.readValue("{}", Patch.class);

        assertThat(patch.note()).isEqualTo(PatchField.absent());
        assertThat(patch.note().isSent()).isFalse();
        assertThat(patch.note().orElse("kept")).isEqualTo("kept");
    }

    @Test
    void anExplicitNullIsSentAndClears() {
        Patch patch = mapper.readValue("{\"note\": null}", Patch.class);

        assertThat(patch.note().isSent()).isTrue();
        assertThat(patch.note().value()).isNull();
        assertThat(patch.note().orElse("kept")).isNull();
    }

    @Test
    void aValueGoesThroughTheDeserializerOfItsType() {
        Patch patch = mapper.readValue("{\"note\": \"abc\", \"size\": 3}", Patch.class);

        assertThat(patch.note()).isEqualTo(PatchField.of("abc"));
        assertThat(patch.size()).isEqualTo(PatchField.of(3));
    }

    @Test
    void mapTransformsOnlyAValue() {
        assertThat(PatchField.of(" a ").map(String::strip)).isEqualTo(PatchField.of("a"));
        assertThat(PatchField.<String>of(null).map(String::strip)).isEqualTo(PatchField.of(null));
        assertThat(PatchField.<String>absent().map(String::strip)).isEqualTo(PatchField.absent());
    }

    @Test
    void validatesTheValueInsideWithTheNameOfTheField() {
        Set<ConstraintViolation<Patch>> violations =
                validator.validate(mapper.readValue("{\"note\": \"abcd\"}", Patch.class));

        assertThat(violations)
                .singleElement()
                .satisfies(violation ->
                        assertThat(violation.getPropertyPath().toString()).isEqualTo("note"));
    }

    @Test
    void neitherAbsenceNorNullBreakTheConstraintsInside() {
        assertThat(validator.validate(mapper.readValue("{}", Patch.class))).isEmpty();
        assertThat(validator.validate(mapper.readValue("{\"note\": null}", Patch.class)))
                .isEmpty();
    }
}
