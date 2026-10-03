package io.github.ricardoord.opswatch.egress;

import org.jspecify.annotations.Nullable;

/**
 * The first rule of {@link HeaderPolicy} that a list of headers breaks. The message never quotes a value.
 *
 * @param index the position of the header, or -1 when the rule is about the whole list
 * @param part {@code name} or {@code value}; null when the rule is about the whole list
 * @param code kebab-case, in the style of the validation errors of the API
 */
public record HeaderViolation(int index, @Nullable String part, String code, String message) {

    /** The path of the offending field under {@code list}: {@code headers[2].name}, or {@code headers} itself. */
    public String field(String list) {
        return index < 0 ? list : list + "[" + index + "]" + (part == null ? "" : "." + part);
    }
}
