package io.github.ricardoord.opswatch.shared.error;

/**
 * The resource does not exist, is deleted or belongs to an organization the user is not a member of (404). The
 * detail is the same in all three cases so that other tenants' resources cannot be discovered.
 */
public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String resource, Object id) {
        super(ProblemCode.RESOURCE_NOT_FOUND, resource + " " + id + " was not found");
    }
}
