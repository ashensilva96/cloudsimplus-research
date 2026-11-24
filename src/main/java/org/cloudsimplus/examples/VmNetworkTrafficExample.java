/*
 * CloudSim Plus example, distributed under the project's GPLv3 license.
 */
package org.cloudsimplus.examples;

import ch.qos.logback.classic.Level;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.core.CloudSimEntity;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.core.CloudSimTag;
import org.cloudsimplus.core.events.SimEvent;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.util.Log;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Paced, directional synthetic traffic between actual CloudSim VM objects.
 * CloudSim manages VM placement and the event clock; this example's entity
 * explicitly models two independent FIFO links, one per direction (full duplex).
 * This is an application-level batch model, not TCP/UDP or the native switch model.
 * It deliberately does not infer CPU overhead or modify migration bandwidth.
 */
public final class VmNetworkTrafficExample {
    private static final double BITS_PER_MEGABIT = 1_000_000;
    private static final double START_TIME = 1;
    private static final double EPSILON = 1e-9;

    private VmNetworkTrafficExample() { }

    public static void main(final String[] args) throws IOException {
        if (List.of(args).contains("--help")) {
            System.out.println("""
                VM traffic simulation (rates in decimal Mbps, times in simulated seconds).
                No rate flags: baseline plus incoming/outgoing/both for --rates.
                --incoming N --outgoing N  Run one case (unspecified direction = 0).
                --rates 100,500,900,1200    Rates for the default experiment suite.
                --capacity 1000            Fixed capacity per direction.
                --duration 10              Traffic generation window.
                --interval 0.1             Time between generated batches.
                --latency 0.001            One-way propagation delay.
                --output output/network-traffic   Directory for summary.csv and batches.csv.
                """);
            return;
        }
        final Map<String, String> options = parseOptions(args);
        final var output = Path.of(options.getOrDefault("output", "output/network-traffic"));
        final double capacity = number(options, "capacity", 1000);
        final double duration = number(options, "duration", 10);
        final double interval = number(options, "interval", 0.1);
        final double latency = number(options, "latency", 0.001);
        final List<RunResult> results = new ArrayList<>();
        Log.setLevel(Level.WARN);

        if (options.containsKey("incoming") || options.containsKey("outgoing")) {
            if (options.containsKey("rates")) {
                throw new IllegalArgumentException("Use --rates for a suite or --incoming/--outgoing for one case.");
            }
            results.add(run("custom", new Config(number(options, "incoming", 0),
                number(options, "outgoing", 0), capacity, duration, interval, latency)));
        } else {
            results.add(run("idle", new Config(0, 0, capacity, duration, interval, latency)));
            int index = 0;
            for (final String value : options.getOrDefault("rates", "100,500,900,1200").split(",", -1)) {
                final double rate = Double.parseDouble(value);
                index++;
                results.add(run("incoming-" + index, new Config(rate, 0, capacity, duration, interval, latency)));
                results.add(run("outgoing-" + index, new Config(0, rate, capacity, duration, interval, latency)));
                results.add(run("both-" + index, new Config(rate, rate, capacity, duration, interval, latency)));
            }
        }
        writeResults(output, results);
        System.out.println("case             direction  offered Mbps  delivered Mbps*  max queue ms  drain s");
        for (final RunResult result : results) {
            for (final FlowResult flow : result.flows()) {
                System.out.printf(Locale.ROOT, "%-16s %-9s %12.2f %16.2f %13.3f %8.3f%n",
                    result.name(), flow.direction(), flow.offeredMbps(), flow.windowMbps(),
                    flow.maxQueueSeconds() * 1000, flow.drainSeconds());
            }
        }
        System.out.println("* Delivery during the generation window; queued batches drain afterwards.");
        System.out.println("CSV files: " + output.toAbsolutePath());
    }

    static RunResult run(final String name, final Config config) {
        final var simulation = new CloudSimPlus(0.000001);
        final List<Host> hosts = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            // One PE per host ensures that the two one-PE VMs occupy different hosts.
            hosts.add(new HostSimple(4096, (long)Math.ceil(config.capacityMbps()),
                100_000, List.of(new PeSimple(1000))));
        }
        new DatacenterSimple(simulation, hosts);
        final var broker = new DatacenterBrokerSimple(simulation);
        broker.setShutdownWhenIdle(false);
        final Vm target = new VmSimple(1000, 1).setRam(1024).setBw(1).setSize(1000);
        final Vm peer = new VmSimple(1000, 1).setRam(1024).setBw(1).setSize(1000);
        broker.submitVmList(List.of(target, peer));
        final var traffic = new TrafficEntity(simulation, broker, target, peer, config);
        simulation.start();
        return traffic.result(name);
    }

    /** Values refer to the target VM; link capacity is independent of offered rate. */
    record Config(double incomingMbps, double outgoingMbps, double capacityMbps,
                  double duration, double interval, double latency) {
        Config {
            if (!Double.isFinite(incomingMbps) || incomingMbps < 0 ||
                !Double.isFinite(outgoingMbps) || outgoingMbps < 0 ||
                !Double.isFinite(capacityMbps) || capacityMbps < 1 || capacityMbps > 1e9 ||
                !Double.isFinite(duration) || duration <= 0 ||
                !Double.isFinite(interval) || interval < 0.000001 ||
                !Double.isFinite(latency) || latency < 0) {
                throw new IllegalArgumentException("Rates must be finite and nonnegative; capacity 1..1e9 Mbps, " +
                    "duration > 0, interval >= 1e-6 s, latency >= 0.");
            }
            if (Math.ceil(duration / interval) > 1_000_000 ||
                Math.max(incomingMbps, outgoingMbps) * BITS_PER_MEGABIT / 8 * duration > 1e15) {
                throw new IllegalArgumentException("Use at most 1,000,000 intervals and 1e15 bytes per flow.");
            }
        }
    }

    record FlowResult(String direction, long sourceVm, long destinationVm, long sourceHost,
                      long destinationHost, double offeredMbps, long generatedBytes,
                      long receivedBytes, long windowReceivedBytes, double windowMbps,
                      double maxQueueSeconds, double meanDelaySeconds, double drainSeconds) { }

    record Batch(String direction, long sourceVm, long destinationVm, long bytes,
                 double generatedAt, double receivedAt, double queueSeconds) { }

    record RunResult(String name, Config config, List<FlowResult> flows, List<Batch> batches) { }

    /** A lossless FIFO serializer. The two instances do not share capacity. */
    private static final class Flow {
        private final String direction;
        private final Vm source;
        private final Vm destination;
        private final double rate;
        private long sourceHost;
        private long destinationHost;
        private long generated;
        private long received;
        private long windowReceived;
        private double availableAt;
        private double lastArrival;
        private double maxQueue;
        private double weightedDelay;

        private Flow(final String direction, final Vm source, final Vm destination, final double rate) {
            this.direction = direction;
            this.source = source;
            this.destination = destination;
            this.rate = rate;
        }

        private FlowResult result(final Config config) {
            return new FlowResult(direction, source.getId(), destination.getId(), sourceHost, destinationHost,
                rate, generated, received, windowReceived, windowReceived * 8 / BITS_PER_MEGABIT / config.duration(),
                maxQueue, received == 0 ? 0 : weightedDelay / received,
                Math.max(0, lastArrival - START_TIME - config.duration()));
        }
    }

    private record Delivery(Flow flow, Batch batch) { }

    private static final class TrafficEntity extends CloudSimEntity {
        private static final int START = 100_001;
        private static final int GENERATE = 100_002;
        private static final int DELIVER = 100_003;
        private static final int STOP_GENERATING = 100_004;
        private final DatacenterBrokerSimple broker;
        private final Config config;
        private final List<Flow> flows;
        private final List<Batch> batches = new ArrayList<>();
        private long pending;
        private boolean generationStopped;

        private TrafficEntity(final CloudSimPlus simulation, final DatacenterBrokerSimple broker,
                              final Vm target, final Vm peer, final Config config) {
            super(simulation);
            this.broker = broker;
            this.config = config;
            flows = List.of(new Flow("incoming", peer, target, config.incomingMbps()),
                new Flow("outgoing", target, peer, config.outgoingMbps()));
        }

        @Override
        protected void startInternal() {
            schedule(START_TIME, START);
        }

        @Override
        public void processEvent(final SimEvent event) {
            switch (event.getTag()) {
                case START -> startTraffic();
                case GENERATE -> generate((Integer)event.getData());
                case DELIVER -> deliver((Delivery)event.getData());
                case STOP_GENERATING -> {
                    generationStopped = true;
                    finishIfDrained();
                }
                case CloudSimTag.SIMULATION_END -> { }
                default -> throw new IllegalArgumentException("Unexpected traffic event: " + event.getTag());
            }
        }

        private void startTraffic() {
            for (final Flow flow : flows) {
                if (!flow.source.isCreated() || !flow.destination.isCreated() ||
                    flow.source.getHost() == flow.destination.getHost()) {
                    throw new IllegalStateException("Expected two created VMs on different hosts at t=1 s.");
                }
                flow.sourceHost = flow.source.getHost().getId();
                flow.destinationHost = flow.destination.getHost().getId();
            }
            schedule(config.duration(), STOP_GENERATING);
            generate(0);
        }

        private void generate(final int index) {
            final double elapsed = Math.min(config.duration(), (index + 1) * config.interval());
            final double now = getSimulation().clock();
            for (final Flow flow : flows) {
                // Cumulative rounding preserves the intended byte total, even for partial final intervals.
                final long cumulative = Math.round(flow.rate * BITS_PER_MEGABIT / 8 * elapsed);
                final long bytes = cumulative - flow.generated;
                flow.generated = cumulative;
                if (bytes == 0) {
                    continue;
                }
                final double serviceStart = Math.max(now, flow.availableAt);
                final double queueSeconds = serviceStart - now;
                flow.availableAt = serviceStart + bytes * 8 / BITS_PER_MEGABIT / config.capacityMbps();
                final double arrival = flow.availableAt + config.latency();
                flow.maxQueue = Math.max(flow.maxQueue, queueSeconds);
                final var batch = new Batch(flow.direction, flow.source.getId(), flow.destination.getId(),
                    bytes, now - START_TIME, arrival - START_TIME, queueSeconds);
                pending++;
                schedule(arrival - now, DELIVER, new Delivery(flow, batch));
            }
            final double next = (index + 1) * config.interval();
            if (next < config.duration()) {
                schedule(START_TIME + next - now, GENERATE, index + 1);
            }
        }

        private void deliver(final Delivery delivery) {
            final Flow flow = delivery.flow();
            final Batch batch = delivery.batch();
            flow.received += batch.bytes();
            if (batch.receivedAt() <= config.duration() + EPSILON) {
                flow.windowReceived += batch.bytes();
            }
            flow.lastArrival = getSimulation().clock();
            flow.weightedDelay += batch.bytes() * (batch.receivedAt() - batch.generatedAt());
            batches.add(batch);
            pending--;
            finishIfDrained();
        }

        private void finishIfDrained() {
            if (generationStopped && pending == 0) {
                // Endpoints are retained until every queued batch arrives, including after the window.
                broker.shutdown();
                shutdown();
            }
        }

        private RunResult result(final String name) {
            if (!generationStopped || pending != 0) {
                throw new IllegalStateException("Simulation stopped before all traffic was delivered.");
            }
            return new RunResult(name, config, flows.stream().map(flow -> flow.result(config)).toList(),
                List.copyOf(batches));
        }
    }

    private static Map<String, String> parseOptions(final String[] args) {
        final var options = new HashMap<String, String>();
        final var allowed = List.of("incoming", "outgoing", "rates", "capacity", "duration", "interval", "latency", "output");
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || !allowed.contains(args[i].substring(2)) || i + 1 == args.length) {
                throw new IllegalArgumentException("Expected --option value; see --help. Invalid option: " + args[i]);
            }
            if (options.put(args[i].substring(2), args[i + 1]) != null) {
                throw new IllegalArgumentException("Duplicate option: " + args[i]);
            }
        }
        return options;
    }

    private static double number(final Map<String, String> options, final String key, final double fallback) {
        return Double.parseDouble(options.getOrDefault(key, Double.toString(fallback)));
    }

    private static void writeResults(final Path output, final List<RunResult> results) throws IOException {
        Files.createDirectories(output);
        final List<String> summary = new ArrayList<>();
        summary.add("case,direction,source_vm,destination_vm,source_host,destination_host,offered_mbps," +
            "capacity_mbps,duration_s,interval_s,propagation_s,generated_bytes,received_bytes," +
            "received_in_window_bytes,window_throughput_mbps,max_queue_ms,mean_delay_ms,drain_s");
        final List<String> trace = new ArrayList<>();
        trace.add("case,direction,source_vm,destination_vm,bytes,generated_at_s,received_at_s,queue_ms");
        for (final RunResult result : results) {
            final Config config = result.config();
            for (final FlowResult flow : result.flows()) {
                summary.add(String.format(Locale.ROOT,
                    "%s,%s,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%d,%.6f,%.6f,%.6f,%.6f",
                    result.name(), flow.direction(), flow.sourceVm(), flow.destinationVm(), flow.sourceHost(),
                    flow.destinationHost(), flow.offeredMbps(), config.capacityMbps(), config.duration(),
                    config.interval(), config.latency(), flow.generatedBytes(), flow.receivedBytes(),
                    flow.windowReceivedBytes(), flow.windowMbps(), flow.maxQueueSeconds() * 1000,
                    flow.meanDelaySeconds() * 1000, flow.drainSeconds()));
            }
            for (final Batch batch : result.batches()) {
                trace.add(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%.9f,%.9f,%.6f", result.name(),
                    batch.direction(), batch.sourceVm(), batch.destinationVm(), batch.bytes(),
                    batch.generatedAt(), batch.receivedAt(), batch.queueSeconds() * 1000));
            }
        }
        Files.write(output.resolve("summary.csv"), summary);
        Files.write(output.resolve("batches.csv"), trace);
    }
}
