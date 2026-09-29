package io.github.ricardoord.opswatch.shared.error;

/** The {@code If-Match} header does not match the current version of the resource (412). */
public class PreconditionFailedException extends DomainException {

    public PreconditionFailedException(String detail) {
        super(ProblemCode.PRECONDITION_FAILED, detail);
    }
}
