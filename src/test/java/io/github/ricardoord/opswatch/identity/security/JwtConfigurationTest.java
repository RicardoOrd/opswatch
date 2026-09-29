package io.github.ricardoord.opswatch.identity.security;

import static io.github.ricardoord.opswatch.TestJwtKeys.pem;
import static io.github.ricardoord.opswatch.TestJwtKeys.rsaKeyPair;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

/** Startup rules of the access token keys. They apply to every environment, not only to deployments. */
class JwtConfigurationTest {

    private static final String PRIVATE_KEY =
            pem("PRIVATE KEY", rsaKeyPair(2048).getPrivate());

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void startsWithAnIssuerAndAPrivateKey() {
        runner.withPropertyValues("opswatch.security.jwt.issuer=https://opswatch.example.com")
                .withPropertyValues("opswatch.security.jwt.private-key=" + PRIVATE_KEY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JwtEncoder.class).hasSingleBean(JwtDecoder.class);
                });
    }

    @Test
    void doesNotStartWithoutAPrivateKey() {
        runner.withPropertyValues("opswatch.security.jwt.issuer=https://opswatch.example.com")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("privateKey"));
    }

    @Test
    void doesNotStartWithoutAnIssuer() {
        runner.withPropertyValues("opswatch.security.jwt.private-key=" + PRIVATE_KEY)
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("issuer"));
    }

    @Test
    void doesNotStartWithAWeakKeyAndNeverPrintsIt() {
        String weak = pem("PRIVATE KEY", rsaKeyPair(1024).getPrivate());

        runner.withPropertyValues("opswatch.security.jwt.issuer=https://opswatch.example.com")
                .withPropertyValues("opswatch.security.jwt.private-key=" + weak)
                .run(context -> {
                    assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .rootCause()
                            .hasMessage("opswatch.security.jwt.private-key must be an RSA key of at least 2048 bits");
                    assertThat(stackTraceOf(context.getStartupFailure())).doesNotContain(weak.substring(40, 80));
                });
    }

    private static String stackTraceOf(Throwable failure) {
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
