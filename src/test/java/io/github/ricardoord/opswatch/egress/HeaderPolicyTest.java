package io.github.ricardoord.opswatch.egress;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Layer 4 of docs/security/ssrf-protection.md, with cases 20 and 21 of its table of mandatory tests. */
class HeaderPolicyTest {

    @Test
    void acceptsTheHeadersOfAnOrdinaryApi() {
        assertThat(HeaderPolicy.check(List.of(
                        header("Authorization", "Bearer s3cr3t"),
                        header("X-Api-Key", "abc123"),
                        header("Accept", "application/json"),
                        header("X-Empty", ""),
                        header("X-Tab", "a\tb"))))
                .isEmpty();
        assertThat(HeaderPolicy.check(List.of())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Host",
                "content-length",
                "Transfer-Encoding",
                "Connection",
                "Upgrade",
                "TE",
                "Trailer",
                "Expect",
                "Cookie",
                "Forwarded",
                "Proxy-Authorization",
                "proxy-connection",
                "X-Forwarded-For",
                "x-forwarded-host",
                "X-aws-ec2-metadata-token",
                "X-AWS-EC2-METADATA-TOKEN-TTL-SECONDS"
            })
    void rejectsTheNamesThatSmuggleRequestsOrOpenCloudMetadata(String name) {
        assertViolation(List.of(header(name, "x")), "headers[0].name", "forbidden");
    }

    /** Case 20. */
    @Test
    void rejectsTheHeadersOfTheMetadataOfGoogleAndOracle() {
        assertViolation(List.of(header("Metadata-Flavor", "Google")), "headers[0].name", "forbidden");
        for (String oracle : List.of("Bearer Oracle", "bearer oracle", " Bearer   Oracle ", "Bearer\tOracle")) {
            assertViolation(List.of(header("Authorization", oracle)), "headers[0].value", "forbidden");
        }
        assertThat(HeaderPolicy.check(List.of(header("Authorization", "Bearer Oracle-token-of-a-real-api"))))
                .isEmpty();
    }

    /** Case 21: a line break would split one header in two, or end the headers and start a body. */
    @ParameterizedTest
    @ValueSource(strings = {"value\r\nX-Injected: yes", "value\nX-Injected: yes", "value\r", "nul\u0000", "del\u007F"})
    void rejectsLineBreaksAndControlCharactersInAValue(String value) {
        assertViolation(List.of(header("X-Api-Key", value)), "headers[0].value", "invalid-value");
    }

    @Test
    void rejectsCharactersOutsideAsciiInAValue() {
        assertViolation(
                List.of(header("X-Api-Key", "caf" + Character.toString(0xE9))), "headers[0].value", "invalid-value");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "X Api", "X-Api:", "X-Api\r\n", "(comment)", "X/Api", "\"quoted\""})
    void aNameIsAnHttpToken(String name) {
        assertViolation(List.of(header(name, "x")), "headers[0].name", "invalid-name");
    }

    @Test
    void acceptsEveryCharacterOfTheTokenGrammar() {
        assertThat(HeaderPolicy.check(List.of(header("!#$%&'*+-.^_`|~09AZaz", "x"))))
                .isEmpty();
    }

    @Test
    void limitsTheNumberOfHeadersAndTheirLength() {
        List<RequestHeader> ten = new ArrayList<>(IntStream.range(0, 10)
                .mapToObj(i -> header("X-Header-" + i, "x"))
                .toList());
        assertThat(HeaderPolicy.check(ten)).isEmpty();
        ten.add(header("X-Header-10", "x"));
        assertViolation(ten, "headers", "too-many");

        assertThat(HeaderPolicy.check(List.of(header("X-Long", "v".repeat(1024)))))
                .isEmpty();
        assertViolation(List.of(header("X-Long", "v".repeat(1025))), "headers[0].value", "too-long");
        assertThat(HeaderPolicy.check(List.of(header("X".repeat(256), "x")))).isEmpty();
        assertViolation(List.of(header("X".repeat(257), "x")), "headers[0].name", "too-long");
    }

    @Test
    void aNameAppearsOnceWhateverItsCase() {
        assertViolation(
                List.of(header("X-Api-Key", "a"), header("Accept", "b"), header("x-api-key", "c")),
                "headers[2].name",
                "duplicate");
    }

    /** The first header that breaks a rule is the one reported, so the client can fix them in order. */
    @Test
    void reportsTheFirstHeaderThatBreaksARule() {
        assertViolation(
                List.of(header("Accept", "ok"), header("Host", "x"), header("X-Bad", "a\nb")),
                "headers[1].name",
                "forbidden");
    }

    @Test
    void neverQuotesAValueOrPrintsOne() {
        Optional<HeaderViolation> violation = HeaderPolicy.check(List.of(header("X-Api-Key", "s3cr3t\r\n")));

        assertThat(violation)
                .get()
                .extracting(HeaderViolation::message)
                .asString()
                .doesNotContain("s3cr3t");
        assertThat(header("Authorization", "Bearer s3cr3t").toString())
                .isEqualTo("RequestHeader[name=Authorization, value=<redacted>]");
    }

    private static void assertViolation(List<RequestHeader> headers, String field, String code) {
        assertThat(HeaderPolicy.check(headers)).get().satisfies(violation -> {
            assertThat(violation.field("headers")).isEqualTo(field);
            assertThat(violation.code()).isEqualTo(code);
        });
    }

    private static RequestHeader header(String name, String value) {
        return new RequestHeader(name, value);
    }
}
