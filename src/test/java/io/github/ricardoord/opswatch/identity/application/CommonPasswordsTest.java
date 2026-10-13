package io.github.ricardoord.opswatch.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.identity.domain.PasswordRules;
import io.github.ricardoord.opswatch.shared.error.InvalidFieldException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

class CommonPasswordsTest {

    private static final String RESOURCE_PATH = "identity/common-passwords.txt";

    private final CommonPasswords commonPasswords = new CommonPasswords(List.of("qwertyqwerty", "contraseñasegura"));

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(CommonPasswords.class);

    @Test
    void comparesInLowercase() {
        assertThat(commonPasswords.contains("qwertyqwerty")).isTrue();
        assertThat(commonPasswords.contains("QwertyQWERTY")).isTrue();
        assertThat(commonPasswords.contains("qwertyqwerty ")).isFalse();
        assertThat(commonPasswords.contains("qwertyqwertz")).isFalse();
    }

    @Test
    void lowercasesCharactersBeyondAscii() {
        assertThat(commonPasswords.contains("CONTRASEÑASEGURA")).isTrue();
        // Entries are normalized too, whatever the resource holds
        assertThat(new CommonPasswords(List.of("ÁRBOLÁRBOLÁRBOL")).contains("árbolárbolárbol"))
                .isTrue();
    }

    @Test
    void doesNotDependOnTheDefaultLocale() {
        Locale defaultLocale = Locale.getDefault();
        // In Turkish, "I" lowercases to a dotless "ı"
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(commonPasswords.contains("QWERTYQWERTY")).isTrue();
            assertThat(new CommonPasswords(List.of("IIIIIIIIIIII")).contains("iiiiiiiiiiii"))
                    .isTrue();
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    void rejectsACommonPasswordWithoutRepeatingIt() {
        assertThatThrownBy(() -> commonPasswords.rejectIfCommon("password", "QwertyQwerty"))
                .isInstanceOf(InvalidFieldException.class)
                .hasMessage(CommonPasswords.MESSAGE)
                .hasMessageNotContainingAny("QwertyQwerty", "qwertyqwerty");
        assertThatNoException().isThrownBy(() -> commonPasswords.rejectIfCommon("password", "correct horse battery"));
    }

    @Test
    void anEmptyListIsAnError() {
        assertThatIllegalStateException().isThrownBy(() -> new CommonPasswords(List.of()));
    }

    @Test
    void theResourceHoldsOnlyPasswordsThatThePasswordRulesAccept() throws IOException {
        String content = new ClassPathResource(RESOURCE_PATH).getContentAsString(StandardCharsets.UTF_8);
        List<String> passwords = content.lines().toList();

        // Shorter ones are already rejected by the length rule, and longer ones cannot be anyone's password
        assertThat(passwords)
                .hasSizeGreaterThan(40_000)
                .allSatisfy(password -> {
                    assertThat(PasswordRules.accepts(password)).as(password).isTrue();
                    assertThat(password).isEqualTo(password.toLowerCase(Locale.ROOT));
                })
                .doesNotHaveDuplicates()
                .isSorted();
        assertThat(content).endsWith("\n").doesNotContain("\r");
    }

    @Test
    void loadsTheResourceAtStartup() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            CommonPasswords loaded = context.getBean(CommonPasswords.class);
            assertThat(loaded.size()).isGreaterThan(40_000);
            assertThat(loaded.contains("QwertyQwerty")).isTrue();
            assertThat(loaded.contains("correct horse battery")).isFalse();
        });
    }

    @Test
    void doesNotStartWithoutTheResource() {
        runner.withClassLoader(new FilteredClassLoader(new ClassPathResource(RESOURCE_PATH)))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining(CommonPasswords.RESOURCE + " is missing"));
    }
}
