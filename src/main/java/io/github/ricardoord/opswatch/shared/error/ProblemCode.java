package io.github.ricardoord.opswatch.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes. Each code has a fixed HTTP status and title and is documented in
 * docs/api/api-guidelines.md (the {@code type} URI of every problem points to its section there).
 */
public enum ProblemCode {
    VALIDATION_ERROR("validation-error", HttpStatus.BAD_REQUEST, "Validation failed"),
    MALFORMED_REQUEST("malformed-request", HttpStatus.BAD_REQUEST, "Malformed request"),
    INVALID_PARAMETER("invalid-parameter", HttpStatus.BAD_REQUEST, "Invalid parameter"),
    UNAUTHENTICATED("unauthenticated", HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_CREDENTIALS("invalid-credentials", HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    ACCESS_DENIED("access-denied", HttpStatus.FORBIDDEN, "Access denied"),
    RESOURCE_NOT_FOUND("resource-not-found", HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED("method-not-allowed", HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    NOT_ACCEPTABLE("not-acceptable", HttpStatus.NOT_ACCEPTABLE, "Not acceptable"),
    CONFLICT("conflict", HttpStatus.CONFLICT, "Conflict"),
    BUSINESS_RULE_VIOLATION("business-rule-violation", HttpStatus.CONFLICT, "Business rule violation"),
    CONCURRENT_MODIFICATION("concurrent-modification", HttpStatus.CONFLICT, "Concurrent modification"),
    PRECONDITION_FAILED("precondition-failed", HttpStatus.PRECONDITION_FAILED, "Precondition failed"),
    UNSUPPORTED_MEDIA_TYPE("unsupported-media-type", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    TARGET_NOT_ALLOWED("target-not-allowed", HttpStatus.UNPROCESSABLE_CONTENT, "Target not allowed"),
    QUOTA_EXCEEDED("quota-exceeded", HttpStatus.UNPROCESSABLE_CONTENT, "Quota exceeded"),
    RATE_LIMITED("rate-limited", HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    INTERNAL_ERROR("internal-error", HttpStatus.INTERNAL_SERVER_ERROR, "Internal error"),
    SERVICE_UNAVAILABLE("service-unavailable", HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable");

    private final String slug;
    private final HttpStatus status;
    private final String title;

    ProblemCode(String slug, HttpStatus status, String title) {
        this.slug = slug;
        this.status = status;
        this.title = title;
    }

    public String slug() {
        return slug;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** Code used for framework errors that only carry a status (unknown route, wrong method, bad body...). */
    static ProblemCode forStatus(int status) {
        return switch (status) {
            case 401 -> UNAUTHENTICATED;
            case 403 -> ACCESS_DENIED;
            case 404 -> RESOURCE_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 412 -> PRECONDITION_FAILED;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> RATE_LIMITED;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status >= 500 ? INTERNAL_ERROR : MALFORMED_REQUEST;
        };
    }
}
