package io.github.ricardoord.opswatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.testcontainers.lifecycle.TestcontainersLifecycleApplicationContextInitializer;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

class PostgresTestcontainerIT {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new TestcontainersLifecycleApplicationContextInitializer())
            .withUserConfiguration(PostgresTestcontainer.class);

    @Test
    void sharesOneContainerAcrossContextsAndOutlivesThem() {
        List<String> containerIds = new ArrayList<>();

        runner.run(context -> containerIds.add(containerId(context)));
        runner.withPropertyValues("a.different=context").run(context -> containerIds.add(containerId(context)));

        // A container stopped with the first context would be started again, as a new one, by the second
        assertThat(containerIds).hasSize(2).doesNotContainNull();
        assertThat(containerIds.get(1)).isEqualTo(containerIds.get(0));
        assertThat(isRunning(containerIds.get(0))).isTrue();
    }

    @Test
    void usesThePostgresImagePinnedInDockerCompose() {
        assertThat(PostgresTestcontainer.composeImage().asCanonicalNameString())
                .matches("postgres@sha256:[0-9a-f]{64}");
    }

    private static String containerId(AssertableApplicationContext context) {
        return context.getBean(PostgreSQLContainer.class).getContainerId();
    }

    private static boolean isRunning(String containerId) {
        return Boolean.TRUE.equals(DockerClientFactory.instance()
                .client()
                .inspectContainerCmd(containerId)
                .exec()
                .getState()
                .getRunning());
    }
}
