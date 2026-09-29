package io.github.ricardoord.opswatch.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChangePasswordRequestTest {

    @Test
    void toStringNeverShowsThePasswords() {
        var request = new ChangePasswordRequest("correct horse battery", "another long passphrase");

        assertThat(request.toString())
                .doesNotContain("correct horse battery")
                .doesNotContain("another long passphrase");
    }
}
