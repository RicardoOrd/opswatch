package io.github.ricardoord.opswatch.shared.text;

import java.util.regex.Pattern;

/**
 * Names that other people see: of users, organizations, projects and monitors. They cannot carry control characters,
 * line or paragraph separators, or bidirectional overrides that would disguise them. Each field sets its own length.
 */
public final class VisibleText {

    /** For {@code @Pattern} on request fields. */
    public static final String PATTERN = "[^\\p{Cc}\\p{Zl}\\p{Zp}\\u202A-\\u202E\\u2066-\\u2069]*";

    /** The message of that {@code @Pattern}, the same on every field. */
    public static final String MESSAGE = "must not contain control or bidirectional formatting characters";

    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    private VisibleText() {}

    public static boolean isValid(String text) {
        return COMPILED.matcher(text).matches();
    }
}
