package io.github.ricardoord.opswatch.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.shared.error.PreconditionFailedException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ETagsTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "\"3\"", " \"3\" ", "*", "\"1\", \"3\""})
    void anAbsentOrMatchingIfMatchPasses(String ifMatch) {
        assertThatCode(() -> ETags.requireMatch(ifMatch, 3)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    // An old version, a weak tag (If-Match compares strongly), no quotes, another resource's style
    @ValueSource(strings = {"\"2\"", "W/\"3\"", "3", "\"3-gzip\"", "\"1\", \"2\""})
    void anythingElseIsAFailedPrecondition(String ifMatch) {
        assertThatThrownBy(() -> ETags.requireMatch(ifMatch, 3)).isInstanceOf(PreconditionFailedException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 3, 42})
    void theTagIsTheQuotedVersion(long version) {
        assertThat(ETags.of(version)).isEqualTo("\"" + version + "\"");
    }
}
