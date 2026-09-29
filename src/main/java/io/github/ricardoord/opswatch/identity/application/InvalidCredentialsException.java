package io.github.ricardoord.opswatch.identity.application;

import io.github.ricardoord.opswatch.shared.error.DomainException;
import io.github.ricardoord.opswatch.shared.error.ProblemCode;

/**
 * Failed login (401). One message for every cause (unknown email, wrong password, disabled account), so the response
 * does not reveal which one it was.
 */
public class InvalidCredentialsException extends DomainException {

    static final String DETAIL = "The email or password is incorrect.";

    public InvalidCredentialsException() {
        super(ProblemCode.INVALID_CREDENTIALS, DETAIL);
    }
}
