package io.github.ricardoord.opswatch.shared.error;

/** The target URL is rejected by the SSRF policy (422). */
public class TargetNotAllowedException extends DomainException {

    public TargetNotAllowedException(String detail) {
        super(ProblemCode.TARGET_NOT_ALLOWED, detail);
    }
}
