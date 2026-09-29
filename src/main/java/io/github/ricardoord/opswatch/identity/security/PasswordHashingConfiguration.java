package io.github.ricardoord.opswatch.identity.security;

import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * bcrypt through {@link DelegatingPasswordEncoder}: every hash carries its algorithm ({@code {bcrypt}...}), so moving
 * to Argon2id later only means adding an encoder and upgrading hashes on the next login (ADR-004).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PasswordProperties.class)
public class PasswordHashingConfiguration {

    static final String BCRYPT = "bcrypt";

    @Bean
    PasswordEncoder passwordEncoder(PasswordProperties properties) {
        PasswordEncoder bcrypt = new BCryptPasswordEncoder(properties.bcryptStrength());
        return new DelegatingPasswordEncoder(BCRYPT, Map.of(BCRYPT, bcrypt));
    }
}
