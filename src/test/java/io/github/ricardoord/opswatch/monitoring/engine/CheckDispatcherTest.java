package io.github.ricardoord.opswatch.monitoring.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.ricardoord.opswatch.monitoring.application.CheckResultRecorder;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** How much the dispatcher asks the claim for, and when it asks nothing. */
class CheckDispatcherTest {

    private final CheckClaimer claimer = mock(CheckClaimer.class);
    private CheckDispatcher dispatcher = dispatcher(10, 3);

    @AfterEach
    void stop() {
        dispatcher.destroy();
    }

    @Test
    void asksForAsManyAsItHasPermitsFreeUpToTheBatchSize() {
        dispatcher = dispatcher(2, 500);
        dispatcher.start();
        given(claimer.claim(anyInt())).willReturn(List.of());

        dispatcher.dispatch();

        verify(claimer).claim(2);
    }

    @Test
    void neverAsksForMoreThanTheBatchSize() {
        dispatcher.start();
        given(claimer.claim(anyInt())).willReturn(List.of());

        dispatcher.dispatch();

        verify(claimer).claim(3);
    }

    @Test
    void claimsNothingBeforeItStartsNorAfterItStops() {
        assertThat(dispatcher.dispatch()).isZero();
        dispatcher.start();
        dispatcher.stop();

        assertThat(dispatcher.dispatch()).isZero();
        verify(claimer, never()).claim(anyInt());
    }

    /** The database down: nothing is lost, what is due stays due, and the next dispatch tries again. */
    @Test
    void aClaimThatFailsIsTriedAgainInTheNextDispatch() {
        dispatcher.start();
        given(claimer.claim(anyInt()))
                .willThrow(new DataAccessResourceFailureException("Connection refused"))
                .willReturn(List.of());

        assertThat(dispatcher.dispatch()).isZero();
        assertThat(dispatcher.dispatch()).isZero();

        verify(claimer, times(2)).claim(3);
        assertThat(dispatcher.isRunning()).isTrue();
    }

    private CheckDispatcher dispatcher(int maxConcurrentChecks, int maxBatchSize) {
        MonitoringEngineProperties properties = new MonitoringEngineProperties(
                true,
                Duration.ofSeconds(1),
                maxConcurrentChecks,
                maxBatchSize,
                5,
                Duration.ofMillis(200),
                Duration.ofSeconds(5),
                "OpsWatch-Test/0");
        return new CheckDispatcher(
                claimer,
                request -> {
                    throw new AssertionError("No check runs here");
                },
                mock(CheckResultRecorder.class),
                properties,
                Clock.systemUTC());
    }
}
