package io.github.ricardoord.opswatch.shared.web;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidNullException;

/**
 * For a {@code PATCH} field that can be left out but not set to {@code null} (docs/api/api-guidelines.md): absent, it
 * stays null and the field does not change; an explicit {@code null} is rejected like malformed JSON (400). Use it with
 * {@code @JsonDeserialize(using = NotNullIfPresent.class)}.
 *
 * <p>{@code @JsonSetter(nulls = Nulls.FAIL)} does not do this on a record: Jackson applies it to an absent constructor
 * property too, so an empty {@code PATCH} would fail.
 */
public final class NotNullIfPresent extends ValueDeserializer<Object> {

    private final @Nullable ValueDeserializer<Object> delegate;
    private final @Nullable BeanProperty property;

    public NotNullIfPresent() {
        this(null, null);
    }

    private NotNullIfPresent(@Nullable ValueDeserializer<Object> delegate, @Nullable BeanProperty property) {
        this.delegate = delegate;
        this.property = property;
    }

    /** The usual deserializer of the field's type does the work. */
    @Override
    public ValueDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
        return new NotNullIfPresent(context.findContextualValueDeserializer(property.getType(), property), property);
    }

    @Override
    public Object deserialize(JsonParser parser, DeserializationContext context) {
        return Objects.requireNonNull(delegate, "Only for properties").deserialize(parser, context);
    }

    @Override
    public @Nullable Object getNullValue(DeserializationContext context) {
        BeanProperty field = Objects.requireNonNull(property, "Only for properties");
        throw InvalidNullException.from(context, field.getFullName(), field.getType());
    }

    @Override
    public @Nullable Object getAbsentValue(DeserializationContext context) {
        return null;
    }
}
