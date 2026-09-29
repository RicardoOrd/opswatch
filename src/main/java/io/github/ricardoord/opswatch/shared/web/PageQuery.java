package io.github.ricardoord.opswatch.shared.web;

import io.github.ricardoord.opswatch.shared.error.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * The {@code page}, {@code size} and {@code sort} of an offset-paginated list (docs/api/api-guidelines.md#9-paginación),
 * as a controller parameter. {@link PageQueryArgumentResolver} reads and checks their form; {@link #toPageable} checks
 * the sort against the fields the endpoint allows, which never reach Spring Data unchecked.
 *
 * @param page from 0
 * @param size from 1 to {@link #MAX_SIZE}
 * @param sort in the order the client sent them; empty for the endpoint's default
 */
public record PageQuery(int page, int size, List<Sort.Order> sort) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    /** The last criterion of every sort, so that a page never depends on how the database breaks ties. */
    static final String TIE_BREAKER = "id";

    public PageQuery {
        sort = List.copyOf(sort);
    }

    /**
     * @param sortable the fields the client may sort by, named as in the responses and in the entity
     * @param defaultSort when the client sends no {@code sort}
     * @throws InvalidParameterException for a field outside {@code sortable} (400)
     */
    public Pageable toPageable(Set<String> sortable, Sort defaultSort) {
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            if (!sortable.contains(order.getProperty())) {
                throw new InvalidParameterException("Cannot sort by '" + order.getProperty() + "'. Allowed: "
                        + String.join(", ", new TreeSet<>(sortable)) + ".");
            }
            orders.add(order);
        }
        Sort requested = orders.isEmpty() ? defaultSort : Sort.by(orders);
        return PageRequest.of(page, size, requested.and(Sort.by(TIE_BREAKER)));
    }
}
