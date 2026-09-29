package io.github.ricardoord.opswatch.shared.error;

import io.github.ricardoord.opswatch.shared.web.RequestIdFilter;
import java.net.URI;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Builds every error body of the API in the same RFC 9457 shape: fixed {@code title} and {@code type} per
 * {@link ProblemCode}, plus the {@code code} and {@code requestId} extension members.
 */
@Component
public class ProblemFactory {

    private final String problemBaseUri;

    public ProblemFactory(
            @Value(
                            "${opswatch.api.problem-base-uri:https://github.com/RicardoOrd/opswatch/blob/main/docs/api/api-guidelines.md}")
                    String problemBaseUri) {
        this.problemBaseUri = problemBaseUri;
    }

    public ProblemDetail create(ProblemCode code, String detail, @Nullable String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        return decorate(problem, code, instance);
    }

    public ProblemDetail validation(String detail, List<FieldViolation> errors, @Nullable String instance) {
        ProblemDetail problem = create(ProblemCode.VALIDATION_ERROR, detail, instance);
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Applies the OpsWatch conventions to a problem created by Spring for a framework exception. */
    public ProblemDetail decorate(ProblemDetail problem, ProblemCode code, @Nullable String instance) {
        problem.setType(URI.create(problemBaseUri + "#" + code.slug()));
        problem.setTitle(code.title());
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        problem.setProperty("code", code.slug());
        problem.setProperty("requestId", RequestIdFilter.current());
        return problem;
    }

    /** One invalid field of a request, as listed in the {@code errors} member of a validation problem. */
    public record FieldViolation(String field, String code, String message) {}
}
