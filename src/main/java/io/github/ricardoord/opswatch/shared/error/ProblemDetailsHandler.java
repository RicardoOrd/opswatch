package io.github.ricardoord.opswatch.shared.error;

import io.github.ricardoord.opswatch.shared.error.ProblemFactory.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates every exception that reaches Spring MVC into Problem Details (RFC 9457) with a {@link ProblemCode}.
 * Internal details (stack traces, SQL, exception messages of unexpected errors) never reach the client: they are
 * logged together with the request id.
 *
 * <p>Spring Security's entry point and access denied handler delegate here as well (see
 * {@link ProblemDetailsSecurityHandlers}), so authentication errors share the same format.
 */
@RestControllerAdvice
public class ProblemDetailsHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);
    private static final String GENERIC_SERVER_ERROR =
            "An unexpected error occurred. Quote the requestId when reporting it.";
    private static final String BEARER = "Bearer";

    private final ProblemFactory problems;

    public ProblemDetailsHandler(ProblemFactory problems) {
        this.problems = problems;
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException ex, HttpServletRequest request) {
        log.debug("Domain error {}: {}", ex.code().slug(), ex.getMessage());
        ProblemDetail problem = problems.create(ex.code(), ex.getMessage(), request.getRequestURI());
        if (ex.code().status() == HttpStatus.UNAUTHORIZED) {
            // RFC 9110: every 401 names the authentication scheme to use
            return ResponseEntity.status(problem.getStatus())
                    .header(HttpHeaders.WWW_AUTHENTICATE, BEARER)
                    .body(problem);
        }
        return respond(problem);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(
            OptimisticLockingFailureException ex, HttpServletRequest request) {
        return respond(problems.create(
                ProblemCode.CONCURRENT_MODIFICATION,
                "The resource was modified by another request. Reload it and try again.",
                request.getRequestURI()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        ProblemDetail problem = problems.create(
                ProblemCode.UNAUTHENTICATED,
                "A valid access token is required to access this resource.",
                request.getRequestURI());
        // RFC 6750: a token that was sent but rejected is "invalid_token"; without a token, the scheme alone. The
        // reason (expired, bad signature...) stays out of the response
        String challenge = ex instanceof OAuth2AuthenticationException ? BEARER + " error=\"invalid_token\"" : BEARER;
        return ResponseEntity.status(problem.getStatus())
                .header(HttpHeaders.WWW_AUTHENTICATE, challenge)
                .body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(problems.create(
                ProblemCode.ACCESS_DENIED, "You are not allowed to perform this action.", request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(problems.create(ProblemCode.INTERNAL_ERROR, GENERIC_SERVER_ERROR, request.getRequestURI()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.add(new FieldViolation(error.getField(), kebab(error.getCode()), message(error)));
        }
        ex.getBindingResult()
                .getGlobalErrors()
                .forEach(error ->
                        errors.add(new FieldViolation(error.getObjectName(), kebab(error.getCode()), message(error))));
        return validation(errors, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        ex.getParameterValidationResults()
                .forEach(result -> result.getResolvableErrors()
                        .forEach(error -> errors.add(new FieldViolation(
                                String.valueOf(result.getMethodParameter().getParameterName()),
                                kebab(lastCode(error)),
                                message(error)))));
        return validation(errors, headers, request);
    }

    /** Called by Spring for every framework exception it handles; applies the OpsWatch conventions. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail problem = body instanceof ProblemDetail detail ? detail : ProblemDetail.forStatus(statusCode);
        ProblemCode code =
                isParameterError(ex) ? ProblemCode.INVALID_PARAMETER : ProblemCode.forStatus(statusCode.value());
        if (statusCode.is5xxServerError()) {
            log.error("Framework error handling {}", path(request), ex);
            problem.setDetail(GENERIC_SERVER_ERROR);
        }
        problems.decorate(problem, code, path(request));
        return ResponseEntity.status(statusCode).headers(headers).body(problem);
    }

    private ResponseEntity<Object> validation(List<FieldViolation> errors, HttpHeaders headers, WebRequest request) {
        String detail =
                "The request contains " + errors.size() + " invalid " + (errors.size() == 1 ? "field." : "fields.");
        ProblemDetail problem = problems.validation(detail, errors, path(request));
        return ResponseEntity.status(problem.getStatus()).headers(headers).body(problem);
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static boolean isParameterError(Exception ex) {
        return ex instanceof TypeMismatchException || ex instanceof ServletRequestBindingException;
    }

    private static @Nullable String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet
                ? servlet.getRequest().getRequestURI()
                : null;
    }

    private static String message(MessageSourceResolvable error) {
        String message = error.getDefaultMessage();
        return message != null ? message : "is invalid";
    }

    private static @Nullable String lastCode(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return codes == null || codes.length == 0 ? null : codes[codes.length - 1];
    }

    /** {@code NotBlank} becomes {@code not-blank}, matching the kebab-case style of the problem codes. */
    static String kebab(@Nullable String constraint) {
        if (constraint == null || constraint.isBlank()) {
            return "invalid";
        }
        return constraint.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
