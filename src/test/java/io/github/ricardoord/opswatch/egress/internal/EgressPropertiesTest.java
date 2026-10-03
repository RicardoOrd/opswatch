package io.github.ricardoord.opswatch.egress.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** A wrong egress setting stops the application, naming the property, instead of being ignored. */
class EgressPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EgressProperties.class)
    static class Properties {}

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Properties.class);

    @Test
    void defaultsToNoPrivateBlockAndTwoSeconds() {
        runner.run(context -> {
            EgressProperties properties = context.getBean(EgressProperties.class);
            assertThat(properties.allowedPrivateCidrs()).isEmpty();
            assertThat(properties.saveResolutionTimeout()).hasSeconds(2);
        });
    }

    @Test
    void readsACommaSeparatedListOfBlocks() {
        runner.withPropertyValues("opswatch.egress.allowed-private-cidrs=127.0.0.1/32, 172.18.0.0/16")
                .run(context -> assertThat(
                                context.getBean(EgressProperties.class).allowedPrivateBlocks())
                        .hasSize(2));
    }

    @Test
    void refusesToStartWithABlockThatIsNotOne() {
        runner.withPropertyValues("opswatch.egress.allowed-private-cidrs=localhost/8")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("opswatch.egress.allowed-private-cidrs: Not an address literal"));
    }

    @Test
    void refusesToStartWithoutADeadlineForDns() {
        runner.withPropertyValues("opswatch.egress.save-resolution-timeout=0s")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("opswatch.egress.save-resolution-timeout"));
    }
}
