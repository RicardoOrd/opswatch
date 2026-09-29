package io.github.ricardoord.opswatch.shared.web;

import io.github.ricardoord.opswatch.shared.error.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link PageQuery} parameters. A value out of range is an error ({@code 400 invalid-parameter}), never
 * silently corrected: a client asking for 500 items must learn that it gets at most 100.
 *
 * <p>Each {@code sort} parameter is one field with an optional direction ({@code sort=name,asc&sort=createdAt,desc}).
 * Spring's own conversion would split a single {@code name,asc} into two values, so they are read as sent.
 */
public class PageQueryArgumentResolver implements HandlerMethodArgumentResolver {

    private static final Pattern SORT =
            Pattern.compile("([A-Za-z][A-Za-z0-9]*)(?:,(asc|desc))?", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == PageQuery.class;
    }

    @Override
    public PageQuery resolveArgument(
            MethodParameter parameter,
            @Nullable ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            @Nullable WebDataBinderFactory binderFactory) {
        int page = number(webRequest.getParameter("page"), 0, 0, Integer.MAX_VALUE, "page");
        int size = number(webRequest.getParameter("size"), PageQuery.DEFAULT_SIZE, 1, PageQuery.MAX_SIZE, "size");
        return new PageQuery(page, size, sort(webRequest.getParameterValues("sort")));
    }

    private static int number(@Nullable String value, int defaultValue, int min, int max, String name) {
        if (value == null) {
            return defaultValue;
        }
        try {
            int number = Integer.parseInt(value.strip());
            if (number >= min && number <= max) {
                return number;
            }
        } catch (NumberFormatException ex) {
            // Reported below, like a number out of range
        }
        throw new InvalidParameterException("'" + name + "' must be a whole number from " + min
                + (max == Integer.MAX_VALUE ? "" : " to " + max) + ".");
    }

    private static List<Sort.Order> sort(String @Nullable [] values) {
        List<Sort.Order> orders = new ArrayList<>();
        if (values == null) {
            return orders;
        }
        for (String value : values) {
            Matcher matcher = SORT.matcher(value.strip());
            if (!matcher.matches()) {
                throw new InvalidParameterException(
                        "'sort' must be a field with an optional direction, like name,asc.");
            }
            String direction = matcher.group(2);
            orders.add(
                    direction != null && direction.toLowerCase(Locale.ROOT).equals("desc")
                            ? Sort.Order.desc(matcher.group(1))
                            : Sort.Order.asc(matcher.group(1)));
        }
        return orders;
    }
}
