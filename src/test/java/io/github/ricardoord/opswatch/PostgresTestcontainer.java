package io.github.ricardoord.opswatch;

import java.util.Properties;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.FileSystemResource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL for integration tests, via {@code @Import(PostgresTestcontainer.class)}.
 *
 * <p>One container per JVM, shared by every Spring context that imports this configuration, however else those
 * contexts differ. The image comes from docker-compose.yml, the only place where it is pinned and where Dependabot
 * updates it, so the tests run against the same PostgreSQL as local development and production.
 *
 * <p>With {@code testcontainers.reuse.enable=true} in {@code ~/.testcontainers.properties} the container also survives
 * between runs (docs/testing/testing-strategy.md#rapidez). Never in CI.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

    static final String COMPOSE_FILE = "docker-compose.yml";

    private static final PostgreSQLContainer POSTGRES = new JvmScopedPostgreSQLContainer(composeImage());

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    static DockerImageName composeImage() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(COMPOSE_FILE));
        Properties compose = yaml.getObject();
        String image = compose == null ? null : compose.getProperty("services.postgres.image");
        if (image == null) {
            throw new IllegalStateException("No services.postgres.image in " + COMPOSE_FILE);
        }
        // Testcontainers does not parse "name:tag@digest": the digest alone pins the image
        return DockerImageName.parse(image.replaceFirst(":[^@/]+@", "@"));
    }

    /**
     * Spring closes a container bean when its context closes (cache eviction, {@code @DirtiesContext}). The shared
     * container must outlive every context, so stopping it is a no-op: Ryuk removes it when the JVM exits.
     */
    private static final class JvmScopedPostgreSQLContainer extends PostgreSQLContainer {

        JvmScopedPostgreSQLContainer(DockerImageName image) {
            super(image);
            withReuse(true);
        }

        @Override
        public void stop() {}
    }
}
