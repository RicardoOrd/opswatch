package io.github.ricardoord.opswatch.shared.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Offset pagination contract of the API (docs/api/api-guidelines.md#9-paginación). A stable DTO instead of Spring
 * Data's {@code Page} serialization, which is not a contract.
 */
public record PageResponse<T>(List<T> items, PageMetadata page) {

    public PageResponse {
        items = List.copyOf(items);
    }

    public static <S, T> PageResponse<T> from(Page<S> page, Function<? super S, ? extends T> mapper) {
        List<T> items = page.getContent().stream().<T>map(mapper).toList();
        return new PageResponse<>(
                items,
                new PageMetadata(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }

    public record PageMetadata(int number, int size, long totalElements, int totalPages) {}
}
