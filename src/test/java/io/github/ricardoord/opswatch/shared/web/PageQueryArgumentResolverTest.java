package io.github.ricardoord.opswatch.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.shared.error.InvalidParameterException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class PageQueryArgumentResolverTest {

    private static final Set<String> SORTABLE = Set.of("name", "createdAt");
    private static final Sort DEFAULT_SORT = Sort.by("name");

    private final PageQueryArgumentResolver resolver = new PageQueryArgumentResolver();

    @Test
    void defaultsToTheFirstPageOf20SortedByTheEndpointDefaultAndThenById() {
        Pageable pageable = resolve(new MockHttpServletRequest()).toPageable(SORTABLE, DEFAULT_SORT);

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort()).isEqualTo(Sort.by("name", "id"));
    }

    @Test
    void readsEachSortParameterAsOneFieldWithItsDirection() {
        var request = new MockHttpServletRequest();
        request.addParameter("page", "2");
        request.addParameter("size", "100");
        request.addParameter("sort", "createdAt,DESC", "name");

        Pageable pageable = resolve(request).toPageable(SORTABLE, DEFAULT_SORT);

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(100);
        assertThat(pageable.getSort().toList())
                .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.asc("name"), Sort.Order.asc("id"));
    }

    @Test
    void aSingleSortWithADirectionIsNotSplitInTwo() {
        var request = new MockHttpServletRequest();
        request.addParameter("sort", "name,desc");

        assertThat(resolve(request).sort()).isEqualTo(List.of(Sort.Order.desc("name")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"101", "0", "-1", "twenty", "1.5", ""})
    void rejectsASizeOutOfRangeInsteadOfCorrectingIt(String size) {
        var request = new MockHttpServletRequest();
        request.addParameter("size", size);

        assertThatThrownBy(() -> resolve(request))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("'size'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "first", "2147483648"})
    void rejectsAnInvalidPage(String page) {
        var request = new MockHttpServletRequest();
        request.addParameter("page", page);

        assertThatThrownBy(() -> resolve(request))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("'page'");
    }

    @ParameterizedTest
    // Paths, expressions and anything that is not a plain field name never reach Spring Data
    @ValueSource(strings = {"o.name", "name,sideways", "name;drop", "", ",asc", "name,asc,desc", "lower(name)"})
    void rejectsASortThatIsNotAFieldAndDirection(String sort) {
        var request = new MockHttpServletRequest();
        request.addParameter("sort", sort);

        assertThatThrownBy(() -> resolve(request)).isInstanceOf(InvalidParameterException.class);
    }

    @Test
    void rejectsAFieldTheEndpointDoesNotAllow() {
        var request = new MockHttpServletRequest();
        request.addParameter("sort", "passwordHash");

        assertThatThrownBy(() -> resolve(request).toPageable(SORTABLE, DEFAULT_SORT))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessage("Cannot sort by 'passwordHash'. Allowed: createdAt, name.");
    }

    private PageQuery resolve(MockHttpServletRequest request) {
        return resolver.resolveArgument(null, null, new ServletWebRequest(request), null);
    }
}
