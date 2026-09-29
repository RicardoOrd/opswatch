package io.github.ricardoord.opswatch.shared.error;

/** A query parameter the endpoint does not accept: a sort field outside its list, a page size over 100 (400). */
public class InvalidParameterException extends DomainException {

    public InvalidParameterException(String detail) {
        super(ProblemCode.INVALID_PARAMETER, detail);
    }
}
