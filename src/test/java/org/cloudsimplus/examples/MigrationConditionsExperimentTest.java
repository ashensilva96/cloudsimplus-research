package org.cloudsimplus.examples;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.cloudsimplus.examples.MigrationConditionsExperiment.*;

class MigrationConditionsExperimentTest {
    private Config config(double rate, Direction direction, Pattern pattern, double dirty) {
        return new Config(8192, 1000, dirty, rate, direction, pattern, 0);
    }

    @Test void zeroDirtyingTransfersExactlyOneMemoryImage() {
        for (final Method method : Method.values()) {
            final var result = simulate(config(0, Direction.OUTGOING, Pattern.STEADY, 0), method);
            assertEquals(65.536, result.totalSeconds(), 1e-9);
            assertEquals(8192, result.transferredMb(), 1e-9);
        }
    }

    @Test void fullDuplexIncomingDoesNotCompeteAndMixedMatchesOutgoing() {
        final var incoming = simulate(config(400, Direction.INCOMING, Pattern.STEADY, 20), Method.POST_COPY);
        final var outgoing = simulate(config(400, Direction.OUTGOING, Pattern.STEADY, 20), Method.POST_COPY);
        final var mixed = simulate(config(400, Direction.MIXED, Pattern.STEADY, 20), Method.POST_COPY);
        assertEquals(65.536, incoming.totalSeconds(), 1e-9);
        assertEquals(8192 * 8.0 / 600, outgoing.totalSeconds(), 1e-9);
        assertEquals(outgoing, mixed);
    }

    @Test void burstIntegrationCrossesOnOffBoundaries() {
        final var config = config(400, Direction.OUTGOING, Pattern.BURSTY, 0);
        // First 10 seconds transfer 250 MB; next 10 transfer 1250 MB.
        assertEquals(20, transferEnd(config, 0, 1500), 1e-9);
        assertEquals(21, transferEnd(config, 0, 1525), 1e-9);
        assertEquals(11, transferEnd(config, 9, 150), 1e-9);
        assertEquals(10, transferEnd(config, 10, 0), 1e-9);
    }

    @Test void highDirtyRateCapsRoundsAndDowntimeIsPartOfTotal() {
        for (final Method method : Method.values()) {
            final var result = simulate(config(400, Direction.OUTGOING, Pattern.BURSTY, 120), method);
            assertTrue(Double.isFinite(result.totalSeconds()));
            assertTrue(result.downtimeSeconds() >= 0);
            assertTrue(result.downtimeSeconds() <= result.totalSeconds());
            assertTrue(result.rounds() <= 8);
        }
        assertEquals(8, simulate(config(400, Direction.OUTGOING, Pattern.STEADY, 120), Method.PRE_COPY).rounds());
    }

    @Test void burstPhaseChangesShortTransferTime() {
        final var on = config(400, Direction.OUTGOING, Pattern.BURSTY, 0);
        final var off = new Config(8192, 1000, 0, 400, Direction.OUTGOING, Pattern.BURSTY, 10);
        assertEquals(4, transferEnd(on, 0, 100), 1e-9);
        assertEquals(0.8, transferEnd(off, 0, 100), 1e-9);
    }

    @Test void invalidOrSaturatedTrafficIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> config(500, Direction.OUTGOING, Pattern.BURSTY, 0));
        assertThrows(IllegalArgumentException.class, () -> config(Double.NaN, Direction.OUTGOING, Pattern.STEADY, 0));
    }
}
