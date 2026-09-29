package io.github.ricardoord.opswatch.shared.time;

import java.time.Clock;
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

    @Bean
    RandomGenerator randomGenerator() {
        return RandomGenerator.getDefault();
    }
}
