package io.github.ricardoord.opswatch.identity.application;

import io.github.ricardoord.opswatch.shared.error.DomainException;
import io.github.ricardoord.opswatch.shared.error.ProblemCode;

/**
 * Failed refresh (401): missing, unknown, expired, revoked or reused token, or a disabled account. One message for
 * every cause. The client has to sign in again.
 */
public class InvalidRefreshTokenException extends DomainException {

    static final String DETAIL = "The session has ended. Sign in again.";

    public InvalidRefreshTokenException() {
        super(ProblemCode.UNAUTHENTICATED, DETAIL);
    }
}
