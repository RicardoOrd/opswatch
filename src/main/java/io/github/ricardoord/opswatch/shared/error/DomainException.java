package io.github.ricardoord.opswatch.shared.error;

/**
 * Base of every error a module reports on purpose. The message becomes the problem's {@code detail}, so it must be
 * meaningful to the client and free of internal data (no SQL, class names or secrets).
 */
public abstract class DomainException extends RuntimeException {

    private final ProblemCode code;

    protected DomainException(ProblemCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ProblemCode code() {
        return code;
    }
}
