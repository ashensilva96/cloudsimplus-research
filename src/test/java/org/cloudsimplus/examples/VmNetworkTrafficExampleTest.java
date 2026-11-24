package org.cloudsimplus.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VmNetworkTrafficExampleTest {
    @Test
    void idleBaselineHasNoTraffic() {
        final var result = run(0, 0, 2, 0.1);
        assertTrue(result.batches().isEmpty());
        for (final var flow : result.flows()) {
            assertEquals(0, flow.generatedBytes());
            assertEquals(0, flow.receivedBytes());
            assertEquals(0, flow.windowMbps());
            assertEquals(0, flow.drainSeconds());
            assertNotEquals(flow.sourceHost(), flow.destinationHost());
        }
    }

    @Test
    void incomingAndOutgoingHaveOppositeEndpointsAndIndependentRates() {
        final var result = run(100, 500, 2, 0.1);
        final var incoming = result.flows().get(0);
        final var outgoing = result.flows().get(1);
        assertEquals("incoming", incoming.direction());
        assertEquals("outgoing", outgoing.direction());
        assertEquals(incoming.destinationVm(), outgoing.sourceVm());
        assertEquals(incoming.sourceVm(), outgoing.destinationVm());
        assertEquals(25_000_000, incoming.receivedBytes());
        assertEquals(125_000_000, outgoing.receivedBytes());
        assertEquals(100, incoming.windowMbps(), 1e-6);
        assertEquals(500, outgoing.windowMbps(), 1e-6);
        assertEquals(0, incoming.maxQueueSeconds(), 1e-9);
        assertEquals(0, outgoing.maxQueueSeconds(), 1e-9);
    }

    @Test
    void outgoingOnlyLeavesIncomingIdle() {
        final var result = run(0, 200, 1, 0.1);
        assertEquals(0, result.flows().get(0).receivedBytes());
        assertEquals(25_000_000, result.flows().get(1).receivedBytes());
        assertTrue(result.batches().stream().allMatch(batch -> batch.direction().equals("outgoing")));
    }

    @Test
    void overloadQueuesAndDrainsWithoutLosingBytesOrExceedingCapacity() {
        final var result = run(2000, 0, 2, 0.1);
        final var flow = result.flows().getFirst();
        assertEquals(500_000_000, flow.generatedBytes());
        assertEquals(flow.generatedBytes(), flow.receivedBytes());
        assertTrue(flow.windowMbps() <= 1000);
        assertTrue(flow.windowMbps() >= 800);
        assertEquals(1.9, flow.maxQueueSeconds(), 1e-8);
        assertEquals(2.001, flow.drainSeconds(), 1e-8);
        assertTrue(flow.windowReceivedBytes() < flow.receivedBytes());
        assertEquals(flow.receivedBytes(), result.batches().stream()
            .mapToLong(VmNetworkTrafficExample.Batch::bytes).sum());
        assertTrue(result.batches().stream().anyMatch(batch -> batch.receivedAt() > 2));
    }

    @Test
    void fullDuplexDoesNotSplitCapacityBetweenDirections() {
        final var result = run(900, 900, 2, 0.1);
        for (final var flow : result.flows()) {
            assertEquals(900, flow.windowMbps(), 1e-6);
            assertEquals(0, flow.maxQueueSeconds(), 1e-8);
        }
    }

    @Test
    void fractionalLastIntervalAndByteRoundingPreserveTotal() {
        final var result = run(0.000123, 0, 0.25, 0.1);
        final var flow = result.flows().getFirst();
        assertEquals(4, flow.generatedBytes());
        assertEquals(4, flow.receivedBytes());
        assertEquals(3, result.batches().size());
        assertEquals(0.2, result.batches().getLast().generatedAt(), 1e-9);
    }

    @Test
    void propagationDelayAffectsDeliveryButNotLinkSerialization() {
        final var result = VmNetworkTrafficExample.run("latency",
            new VmNetworkTrafficExample.Config(100, 0, 1000, 1, 0.1, 2));
        final var flow = result.flows().getFirst();
        assertEquals(0, flow.windowReceivedBytes());
        assertEquals(12_500_000, flow.receivedBytes());
        assertEquals(0, flow.maxQueueSeconds(), 1e-9);
        assertEquals(2.01, flow.meanDelaySeconds(), 1e-9);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
            () -> new VmNetworkTrafficExample.Config(-1, 0, 1000, 1, 0.1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new VmNetworkTrafficExample.Config(1, Double.NaN, 1000, 1, 0.1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new VmNetworkTrafficExample.Config(1, 0, 0, 1, 0.1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new VmNetworkTrafficExample.Config(1, 0, 1000, 0, 0.1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new VmNetworkTrafficExample.Config(1, 0, 1000, 1, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new VmNetworkTrafficExample.Config(1, 0, 1000, 1, 0.1, -1));
    }

    private static VmNetworkTrafficExample.RunResult run(
        final double incoming, final double outgoing, final double duration, final double interval) {
        return VmNetworkTrafficExample.run("test",
            new VmNetworkTrafficExample.Config(incoming, outgoing, 1000, duration, interval, 0.001));
    }
}
