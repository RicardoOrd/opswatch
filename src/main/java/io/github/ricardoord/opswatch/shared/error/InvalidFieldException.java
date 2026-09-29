package io.github.ricardoord.opswatch.shared.error;

import io.github.ricardoord.opswatch.shared.error.ProblemFactory.FieldViolation;

/**
 * A field that passed Bean Validation but that only the application can tell is wrong, such as a current password
 * that does not match. Answered like any validation error (400), with the field in {@code errors}.
 */
public class InvalidFieldException extends DomainException {

    private final String field;
    private final String constraint;

    /**
     * @param constraint kebab-case code, like those of Bean Validation ({@code not-blank})
     * @param message what is wrong with the field, in the style of Bean Validation ({@code must not be blank})
     */
    public InvalidFieldException(String field, String constraint, String message) {
        super(ProblemCode.VALIDATION_ERROR, message);
        this.field = field;
        this.constraint = constraint;
    }

    FieldViolation violation() {
        return new FieldViolation(field, constraint, getMessage());
    }
}
