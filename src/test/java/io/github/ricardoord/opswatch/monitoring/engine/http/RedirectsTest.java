package io.github.ricardoord.opswatch.monitoring.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RedirectsTest {

    private static final URI CURRENT = URI.create("https://api.example.com/v1/health?full=true");

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void theseCodesAreRedirects(int statusCode) {
        assertThat(Redirects.isRedirect(statusCode)).isTrue();
    }

    /** 300 and 304 are no instruction to go elsewhere: they are the final response. */
    @ParameterizedTest
    @ValueSource(ints = {200, 300, 304, 305, 306, 404})
    void theseAreNot(int statusCode) {
        assertThat(Redirects.isRedirect(statusCode)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "/status, https://api.example.com/status",
        "status, https://api.example.com/v1/status",
        "?full=false, https://api.example.com/v1/health?full=false",
        "//cdn.example.com/x, https://cdn.example.com/x",
        "http://other.example.com:8080/x, http://other.example.com:8080/x",
        "/x#section, https://api.example.com/x"
    })
    void resolvesTheLocationAgainstTheCurrentUrlWithoutItsFragment(String location, String expected) {
        assertThat(Redirects.next(CURRENT, redirect(location))).contains(URI.create(expected));
    }

    @Test
    void aRelativeLocationAfterAnEmptyPathStartsAtTheRoot() {
        assertThat(Redirects.next(URI.create("https://api.example.com"), redirect("status")))
                .contains(URI.create("https://api.example.com/status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "http://exa mple.com/", "http://[bad/", "/two words"})
    void aLocationThatIsNotAUriReferenceIsNone(String location) {
        assertThat(Redirects.next(CURRENT, redirect(location))).isEmpty();
    }

    @Test
    void noLocationOrTwoAreNone() {
        BasicClassicHttpResponse none = new BasicClassicHttpResponse(302);
        BasicClassicHttpResponse two = redirect("/a");
        two.addHeader("Location", "/b");

        assertThat(Redirects.next(CURRENT, none)).isEmpty();
        assertThat(Redirects.next(CURRENT, two)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "https://API.example.com:443/other, true",
        "https://api.example.com:8443/, false",
        "http://api.example.com/, false",
        "https://cdn.example.com/, false"
    })
    void anOriginIsSchemeHostAndPort(String other, boolean same) {
        assertThat(Redirects.sameOrigin(CURRENT, URI.create(other))).isEqualTo(same);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ftp://api.example.com/",
                "file:///etc/passwd",
                "mailto:ops@example.com",
                "http:///no-host",
                "http://user:secret@api.example.com/",
                "http://user@api.example.com/"
            })
    void recognisesWhatTheClientRefusesItself(String url) {
        assertThat(Redirects.refusedByTheClient(URI.create(url))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://api.example.com/", "HTTPS://api.example.com:8443/x"})
    void andLetsTheRestReachIt(String url) {
        assertThat(Redirects.refusedByTheClient(URI.create(url))).isFalse();
    }

    private static BasicClassicHttpResponse redirect(String location) {
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(302);
        response.addHeader("Location", location);
        return response;
    }
}
