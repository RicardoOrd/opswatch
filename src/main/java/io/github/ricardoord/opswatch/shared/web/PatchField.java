package io.github.ricardoord.opswatch.shared.web;

import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * A {@code PATCH} field that admits null (docs/api/api-guidelines.md): absent, it does not change; {@code null}
 * clears it; a value replaces it. A plain nullable field cannot tell the first two apart, and {@link NotNullIfPresent}
 * rejects the second.
 *
 * <p>Jackson never leaves it null: an absent property becomes {@link #absent()}. Bean Validation checks the value
 * inside with constraints on the type argument ({@code PatchField<@Size(max = 500) String>}), through
 * {@link PatchFieldValueExtractor}; a null value passes them, as on any field.
 */
@JsonDeserialize(using = PatchField.Deserializer.class)
public final class PatchField<T> {

    private static final PatchField<?> ABSENT = new PatchField<>(false, null);

    private final boolean sent;
    private final @Nullable T value;

    private PatchField(boolean sent, @Nullable T value) {
        this.sent = sent;
        this.value = value;
    }

    /** The field was not in the body. */
    @SuppressWarnings("unchecked")
    public static <T> PatchField<T> absent() {
        return (PatchField<T>) ABSENT;
    }

    /** The field was in the body: {@code null} to clear it. */
    public static <T> PatchField<T> of(@Nullable T value) {
        return new PatchField<>(true, value);
    }

    public boolean isSent() {
        return sent;
    }

    /** Null both when absent and when cleared: ask {@link #isSent()} first. */
    public @Nullable T value() {
        return value;
    }

    /** The value sent, null included, or {@code current} if the field was absent. */
    public @Nullable T orElse(@Nullable T current) {
        return sent ? value : current;
    }

    /** Transforms a value sent; absent and null stay as they are. */
    public <R> PatchField<R> map(Function<? super T, ? extends @Nullable R> mapper) {
        if (!sent) {
            return absent();
        }
        return value == null ? of(null) : of(mapper.apply(value));
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || (other instanceof PatchField<?> field && sent == field.sent && Objects.equals(value, field.value));
    }

    @Override
    public int hashCode() {
        return Objects.hash(sent, value);
    }

    @Override
    public String toString() {
        return sent ? "PatchField[" + value + "]" : "PatchField[absent]";
    }

    /** The value goes through the usual deserializer of its type; {@code null} and absence become the two states. */
    static final class Deserializer extends ValueDeserializer<PatchField<?>> {

        private final @Nullable ValueDeserializer<Object> valueDeserializer;

        Deserializer() {
            this(null);
        }

        private Deserializer(@Nullable ValueDeserializer<Object> valueDeserializer) {
            this.valueDeserializer = valueDeserializer;
        }

        @Override
        public ValueDeserializer<?> createContextual(DeserializationContext context, @Nullable BeanProperty property) {
            JavaType type = property == null ? context.getContextualType() : property.getType();
            JavaType valueType = type == null ? null : type.containedType(0);
            if (valueType == null) {
                valueType = context.constructType(Object.class);
            }
            return new Deserializer(context.findContextualValueDeserializer(valueType, property));
        }

        @Override
        public PatchField<?> deserialize(JsonParser parser, DeserializationContext context) {
            return of(Objects.requireNonNull(valueDeserializer, "Only after createContextual")
                    .deserialize(parser, context));
        }

        @Override
        public PatchField<?> getNullValue(DeserializationContext context) {
            return of(null);
        }

        @Override
        public PatchField<?> getAbsentValue(DeserializationContext context) {
            return absent();
        }
    }
}
