package io.github.ricardoord.opswatch.shared.error;

/** The resource already exists or clashes with another one, such as a duplicate name (409). */
public class ConflictException extends DomainException {

    public ConflictException(String detail) {
        super(ProblemCode.CONFLICT, detail);
    }
}
