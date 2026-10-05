package io.github.ricardoord.opswatch.shared.web;

import io.github.ricardoord.opswatch.shared.error.InvalidParameterException;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link CursorQuery} parameters. A {@code limit} out of range or a cursor that was not written here is an
 * error ({@code 400 invalid-parameter}), never silently corrected.
 */
public class CursorQueryArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == CursorQuery.class;
    }

    @Override
    public CursorQuery resolveArgument(
            MethodParameter parameter,
            @Nullable ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            @Nullable WebDataBinderFactory binderFactory) {
        String cursor = webRequest.getParameter("cursor");
        Instant after = cursor == null ? null : TimeCursor.decode(cursor.strip());
        return new CursorQuery(limit(webRequest.getParameter("limit")), after);
    }

    private static int limit(@Nullable String value) {
        if (value == null) {
            return CursorQuery.DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value.strip());
            if (limit >= 1 && limit <= CursorQuery.MAX_LIMIT) {
                return limit;
            }
        } catch (NumberFormatException ex) {
            // Reported below, like a number out of range
        }
        throw new InvalidParameterException("'limit' must be a whole number from 1 to " + CursorQuery.MAX_LIMIT + ".");
    }
}
