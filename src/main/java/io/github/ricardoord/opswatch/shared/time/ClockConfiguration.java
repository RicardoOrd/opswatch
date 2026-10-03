package io.github.ricardoord.opswatch.shared.time;

import java.time.Clock;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time and randomness as injectable beans, so tests can fix them. Production code never calls {@code Instant.now()}
 * (enforced by ArchitectureRulesTests).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Every request thread shares it: {@link Random} is safe for that, and {@code RandomGenerator.getDefault()} is not.
     * Not for secrets, which use {@code SecureRandom}.
     */
    @Bean
    RandomGenerator randomGenerator() {
        return new Random();
    }
}
