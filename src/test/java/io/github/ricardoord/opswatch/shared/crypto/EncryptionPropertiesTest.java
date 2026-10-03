package io.github.ricardoord.opswatch.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.TestEncryptionKeys;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The application does not start, in any profile, without the active key or with a key that is not 32 bytes
 * (docs/devops/environments.md#salvaguardas-de-arranque). Every message names the property, never the key.
 */
class EncryptionPropertiesTest {

    private static final String KEY = TestEncryptionKeys.randomKey();

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SecretCipher.class);

    @Test
    void startsWithTheActiveKey() {
        runner.withPropertyValues(
                        "opswatch.security.encryption.keys.0=" + KEY, "opswatch.security.encryption.active-key-id=0")
                .run(context -> assertThat(context).hasSingleBean(SecretCipher.class));
    }

    /** As configtree reads a file written by {@code openssl rand -base64 32}, with its line break. */
    @Test
    void acceptsAKeyWithATrailingLineBreak() {
        runner.withPropertyValues(
                        "opswatch.security.encryption.keys.0=" + KEY + "\n",
                        "opswatch.security.encryption.active-key-id=0")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void doesNotStartWithoutAnyKey() {
        runner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessage("opswatch.security.encryption.active-key-id must be set"));
    }

    @Test
    void doesNotStartWhenTheActiveKeyIsNotAmongTheKeys() {
        runner.withPropertyValues(
                        "opswatch.security.encryption.keys.0=" + KEY, "opswatch.security.encryption.active-key-id=1")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessage("opswatch.security.encryption.keys.1 must be set: it is the active key"));
    }

    @Test
    void doesNotStartWithAKeyThatIsNotThirtyTwoBytesAndNeverPrintsIt() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        for (String wrong : new String[] {shortKey, "not-base64-at-all!", KEY + KEY}) {
            runner.withPropertyValues(
                            "opswatch.security.encryption.keys.0=" + KEY,
                            "opswatch.security.encryption.keys.3=" + wrong,
                            "opswatch.security.encryption.active-key-id=0")
                    .run(context -> {
                        assertThat(context)
                                .hasFailed()
                                .getFailure()
                                .rootCause()
                                .hasMessage("opswatch.security.encryption.keys.3 must be a 32-byte AES key in Base64");
                        assertThat(context.getStartupFailure()).hasStackTraceContaining("keys.3");
                        assertThat(stackTrace(context.getStartupFailure()))
                                .doesNotContain(wrong)
                                .doesNotContain(KEY);
                    });
        }
    }

    @Test
    void doesNotStartWithAKeyIdOutsideOneByte() {
        runner.withPropertyValues(
                        "opswatch.security.encryption.keys.0=" + KEY,
                        "opswatch.security.encryption.keys.256=" + KEY,
                        "opswatch.security.encryption.active-key-id=0")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessage("opswatch.security.encryption.keys.256: the id must be between 0 and 255"));
    }

    @Test
    void neverPrintsTheKeys() {
        EncryptionProperties properties = new EncryptionProperties(Map.of(0, KEY, 1, KEY), 1);

        assertThat(properties.toString())
                .isEqualTo("EncryptionProperties[keyIds=[0, 1], activeKeyId=1]")
                .doesNotContain(KEY);
    }

    private static String stackTrace(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }
}
