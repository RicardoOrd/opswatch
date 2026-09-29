package io.github.ricardoord.opswatch.shared.error;

/** The user is a member of the organization but their role lacks the required permission (403). */
public class PermissionDeniedException extends DomainException {

    public PermissionDeniedException(String detail) {
        super(ProblemCode.ACCESS_DENIED, detail);
    }
}
