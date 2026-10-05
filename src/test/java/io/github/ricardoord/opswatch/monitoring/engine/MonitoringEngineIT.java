package io.github.ricardoord.opswatch.monitoring.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.monitoring.domain.CheckStatus;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * The engine switched on, as in a deployment, with a fake client: a monitor due gets checked with nobody calling the
 * dispatcher. A context of its own, closed after the class: while it is open it checks every monitor due in the shared
 * database, and the fake answers for all of them.
 */
@IntegrationTest
@TestPropertySource(
        properties = {"opswatch.monitoring.engine.enabled=true", "opswatch.monitoring.engine.dispatch-interval=100ms"})
@DirtiesContext
class MonitoringEngineIT {

    @TestBean
    private HttpMonitorClient client;

    @Autowired
    private CheckDispatcher dispatcher;

    @Autowired
    private MonitorStateRepository states;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    static HttpMonitorClient client() {
        return request -> new HttpObservation.Response(200, Duration.ofMillis(80), 0);
    }

    @Test
    void checksADueMonitorOnItsOwn() {
        PastSchedule schedule = new PastSchedule(jdbc);
        UUID monitor = schedule.monitorDueAt(clock.instant().minusSeconds(1));

        await().atMost(Duration.ofSeconds(10)).until(() -> schedule.checksOf(monitor) == 1);

        assertThat(dispatcher.isRunning()).isTrue();
        assertThat(states.findById(monitor).orElseThrow().lastCheckStatus()).isEqualTo(CheckStatus.UP);
        assertThat(schedule.nextCheckAt(monitor)).isAfter(clock.instant());
    }
}
