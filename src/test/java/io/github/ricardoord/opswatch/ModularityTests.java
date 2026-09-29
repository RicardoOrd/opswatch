package io.github.ricardoord.opswatch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Module boundaries as a build-breaking test. Allowed dependencies are declared in each module's
 * {@code package-info.java} and documented in docs/architecture/modules.md.
 */
class ModularityTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(OpsWatchApplication.class);

    @Test
    void detectsAllPlannedModules() {
        assertThat(MODULES.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder(
                        "shared", "egress", "identity", "organization", "monitoring", "incident", "notification");
    }

    @Test
    void verifiesModularStructure() {
        // Fails on cycles, access to another module's internal packages and undeclared dependencies
        MODULES.verify();
    }

    @Test
    void writesModuleDocumentation() {
        // Output goes to target/spring-modulith-docs and is published as a CI artifact, not committed
        new Documenter(MODULES)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
    }
}
