package io.github.ricardoord.opswatch.shared.error;

/** An organization or user quota would be exceeded (422). */
public class QuotaExceededException extends DomainException {

    public QuotaExceededException(String detail) {
        super(ProblemCode.QUOTA_EXCEEDED, detail);
    }
}
