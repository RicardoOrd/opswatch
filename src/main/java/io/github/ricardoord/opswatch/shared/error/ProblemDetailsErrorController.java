package io.github.ricardoord.opswatch.shared.error;

import io.github.ricardoord.opswatch.shared.web.RequestIdFilter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Spring Boot's default {@code /error} endpoint, which handles errors raised outside Spring MVC (for example
 * in a servlet filter). Without it those errors would use Boot's own JSON shape instead of Problem Details.
 */
@RestController
public class ProblemDetailsErrorController implements ErrorController {

    private final ProblemFactory problems;

    public ProblemDetailsErrorController(ProblemFactory problems) {
        this.problems = problems;
    }

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        int status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code ? code : 500;
        String path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String uri ? uri : null;
        ProblemCode code = ProblemCode.forStatus(status);
        String detail = status >= 500
                ? "An unexpected error occurred. Quote the requestId when reporting it."
                : code.title() + ".";

        ProblemDetail problem = problems.create(code, detail, path);
        problem.setStatus(status);
        if (problem.getProperties() == null || problem.getProperties().get("requestId") == null) {
            // The error dispatch runs after RequestIdFilter has cleared the MDC; the id travels as a request attribute
            problem.setProperty("requestId", request.getAttribute(RequestIdFilter.MDC_KEY));
        }
        return ResponseEntity.status(status).body(problem);
    }
}
