package io.github.ricardoord.opswatch.identity.web;

import io.github.ricardoord.opswatch.shared.error.DomainException;
import io.github.ricardoord.opswatch.shared.error.ProblemCode;

/** A cookie endpoint called from an origin that is not the API's own or in the CORS list, or without one (403). */
class UntrustedOriginException extends DomainException {

    static final String DETAIL = "The request origin is not allowed.";

    UntrustedOriginException() {
        super(ProblemCode.ACCESS_DENIED, DETAIL);
    }
}
