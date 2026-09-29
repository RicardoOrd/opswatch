package io.github.ricardoord.opswatch.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void reusesSafeClientSuppliedId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "abc-123_XYZ");
        var response = new MockHttpServletResponse();
        var seenDuringRequest = new AtomicReference<String>();

        filter.doFilter(request, response, capturing(seenDuringRequest));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc-123_XYZ");
        assertThat(seenDuringRequest.get()).isEqualTo("abc-123_XYZ");
    }

    @Test
    void generatesIdWhenHeaderIsMissing() throws Exception {
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {});

        assertThat(UUID.fromString(response.getHeader(RequestIdFilter.HEADER))).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "with space", "line\nbreak", "tab\tchar", "semi;colon", "ñandú"})
    void replacesUnsafeIds(String unsafe) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, unsafe);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        String id = response.getHeader(RequestIdFilter.HEADER);
        assertThat(id).isNotEqualTo(unsafe);
        assertThat(UUID.fromString(id)).isNotNull();
    }

    @Test
    void replacesIdsLongerThan64Characters() {
        assertThat(RequestIdFilter.resolve("a".repeat(64))).isEqualTo("a".repeat(64));
        assertThat(RequestIdFilter.resolve("a".repeat(65))).isNotEqualTo("a".repeat(65));
    }

    @Test
    void clearsMdcAfterTheRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {});

        assertThat(RequestIdFilter.current()).isNull();
    }

    private static FilterChain capturing(AtomicReference<String> target) {
        return (req, res) -> target.set(RequestIdFilter.current());
    }
}
