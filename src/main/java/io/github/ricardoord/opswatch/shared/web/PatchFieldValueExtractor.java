package io.github.ricardoord.opswatch.shared.web;

import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.ValueExtractor;

/**
 * Lets Bean Validation check the value inside a {@link PatchField}, and only when the field was sent. Registered in
 * {@code META-INF/services/jakarta.validation.valueextraction.ValueExtractor}. Violations keep the name of the field,
 * as with {@code Optional}.
 */
public final class PatchFieldValueExtractor implements ValueExtractor<PatchField<@ExtractedValue ?>> {

    @Override
    public void extractValues(PatchField<?> field, ValueReceiver receiver) {
        if (field.isSent()) {
            receiver.value(null, field.value());
        }
    }
}
