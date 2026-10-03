package io.github.ricardoord.opswatch;

import io.github.ricardoord.opswatch.egress.internal.FakeHostResolver;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * DNS for the tests that start the application: a {@link FakeHostResolver} in place of the system resolver, so that no
 * test depends on the real DNS or waits for it. Imported by {@link IntegrationTest}. A test that needs a name of its
 * own adds it to the shared resolver with a unique name.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestHostResolver {

    /** Resolves to a public address. */
    public static final String PUBLIC_HOST = "api.example.com";

    /** Resolves to a private address, which the SSRF policy rejects. */
    public static final String PRIVATE_HOST = "internal.example.com";

    @Bean
    @Primary
    FakeHostResolver fakeHostResolver() {
        return new FakeHostResolver().with(PUBLIC_HOST, "93.184.216.34").with(PRIVATE_HOST, "10.0.0.5");
    }
}
