package io.github.ricardoord.opswatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernateProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
class ApplicationStartupIT {

    @Autowired
    private Flyway flyway;

    @Autowired
    private HibernateProperties hibernateProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void connectsToPostgres18() {
        Integer serverVersion = jdbcTemplate.queryForObject("SHOW server_version_num", Integer.class);

        assertThat(serverVersion).isBetween(180000, 189999);
    }

    @Test
    void appliesMigrationsWithFlywayAndOnlyValidatesTheSchemaWithHibernate() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(hibernateProperties.getDdlAuto()).isEqualTo("validate");
    }

    @Test
    void refusesToCleanTheDatabase() {
        assertThatThrownBy(flyway::clean).isInstanceOf(FlywayException.class).hasMessageContaining("disabled");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {

        // postgres:18-alpine, the same digest as docker-compose.yml
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse(
                    "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873"));
        }
    }
}
