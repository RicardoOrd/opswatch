package io.github.ricardoord.opswatch.shared.web;

import io.github.ricardoord.opswatch.shared.error.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** The last criterion of every sort by default, so that a page never depends on how the database breaks ties. */
    static final String TIE_BREAKER = "id";

    public PageQuery {
        sort = List.copyOf(sort);
    }

    /**
     * For entities whose fields have the same names as in the responses, and a unique {@code id}.
     *
     * @param sortable the fields the client may sort by
     * @param defaultSort when the client sends no {@code sort}
     * @throws InvalidParameterException for a field outside {@code sortable} (400)
     */
    public Pageable toPageable(Set<String> sortable, Sort defaultSort) {
        Map<String, String> sameNames = new HashMap<>();
        sortable.forEach(field -> sameNames.put(field, field));
        return toPageable(sameNames, defaultSort, Sort.by(TIE_BREAKER));
    }

    /**
     * @param sortable the fields the client may sort by, each with the entity property it sorts
     * @param defaultSort when the client sends no {@code sort}, in entity properties
     * @param tieBreaker a unique key, in entity properties, that ends every sort
     * @throws InvalidParameterException for a field outside {@code sortable} (400)
     */
    public Pageable toPageable(Map<String, String> sortable, Sort defaultSort, Sort tieBreaker) {
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            String property = sortable.get(order.getProperty());
            if (property == null) {
                throw new InvalidParameterException("Cannot sort by '" + order.getProperty() + "'. Allowed: "
                        + String.join(", ", new TreeSet<>(sortable.keySet())) + ".");
            }
            orders.add(order.withProperty(property));
        }
        Sort requested = orders.isEmpty() ? defaultSort : Sort.by(orders);
        return PageRequest.of(page, size, requested.and(tieBreaker));
    }
}
