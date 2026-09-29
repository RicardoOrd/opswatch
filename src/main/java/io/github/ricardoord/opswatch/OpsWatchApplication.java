package io.github.ricardoord.opswatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// No in-memory user with a generated password: authentication is only ever JWT-based (ADR-004)
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class OpsWatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsWatchApplication.class, args);
    }
}
