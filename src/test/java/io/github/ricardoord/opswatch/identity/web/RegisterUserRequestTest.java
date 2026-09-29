package io.github.ricardoord.opswatch.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RegisterUserRequestTest {

    @Test
    void toStringNeverShowsThePassword() {
        var request = new RegisterUserRequest("ana@example.com", "Ana", "correct horse battery");

        assertThat(request.toString()).contains("ana@example.com").doesNotContain("correct horse battery");
    }

    @Test
    void stripsTheEmailAndTheDisplayNameButNotThePassword() {
        var request = new RegisterUserRequest(" ana@example.com ", " Ana ", " correct horse battery ");

        assertThat(request.email()).isEqualTo("ana@example.com");
        assertThat(request.displayName()).isEqualTo("Ana");
        assertThat(request.password()).isEqualTo(" correct horse battery ");
    }
}
