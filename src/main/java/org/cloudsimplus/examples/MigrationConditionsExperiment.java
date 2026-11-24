package org.cloudsimplus.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Controlled fluid-model sensitivity experiments; not hypervisor measurements. */
public final class MigrationConditionsExperiment {
    private MigrationConditionsExperiment() { }

    enum Method { PRE_COPY, POST_COPY, HYBRID }
    enum Direction { INCOMING, OUTGOING, MIXED }
    enum Pattern { STEADY, BURSTY }

    /** Directions are relative to the source host; destination has no background load. */
    record Config(double memoryMb, double linkMbps, double dirtyMbS, double meanTrafficMbps,
                  Direction direction, Pattern pattern, double phaseSeconds) {
        Config {
            if (!Double.isFinite(memoryMb) || memoryMb < 64 || !Double.isFinite(linkMbps) || linkMbps <= 0
                || !Double.isFinite(dirtyMbS) || dirtyMbS < 0 || !Double.isFinite(meanTrafficMbps)
                || meanTrafficMbps < 0 || !Double.isFinite(phaseSeconds) || phaseSeconds < 0
                || phaseSeconds >= 20 || direction == null || pattern == null
                || meanTrafficMbps * (pattern == Pattern.BURSTY ? 2 : 1) >= linkMbps) {
                throw new IllegalArgumentException("Finite parameters required: memory >=64 MB, link > peak traffic, rates >=0, phase in [0,20).");
            }
        }
    }

    record Metrics(double totalSeconds, double downtimeSeconds, double transferredMb, int rounds) { }

    public static void main(final String[] args) throws IOException {
        final Path output = Path.of(args.length == 0
            ? "output/experiments/migration-conditions.csv" : args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        final List<String> rows = new ArrayList<>();
        rows.add("sweep,method,direction,pattern,memory_mb,link_mbps,dirty_rate_mb_s,mean_traffic_mbps,peak_traffic_mbps,phase_s,total_migration_time_s,downtime_s,transferred_mb,precopy_rounds");
        for (final Direction direction : Direction.values()) {
            for (final double rate : new double[]{0, 100, 250, 400}) {
                for (final Pattern pattern : Pattern.values()) {
                    // Four reproducible alignments of migration start with a 20-second burst cycle.
                    for (final double phase : pattern == Pattern.STEADY ? new double[]{0} : new double[]{0, 5, 10, 15}) {
                        add(rows, "traffic", new Config(8192, 1000, 20, rate, direction, pattern, phase));
                    }
                }
            }
        }
        for (final double memory : new double[]{1024, 2048, 4096, 8192, 16384}) {
            add(rows, "memory", baseline(memory, 1000, 20));
        }
        for (final double link : new double[]{500, 750, 1000, 2000, 10000}) {
            add(rows, "link", baseline(8192, link, 20));
        }
        for (final double dirty : new double[]{0, 5, 20, 50, 80, 120}) {
            add(rows, "dirty-rate", baseline(8192, 1000, dirty));
        }
        Files.write(output, rows);
        System.out.printf("Wrote %d controlled model runs to %s%n", rows.size() - 1, output);
    }

    private static Config baseline(final double memory, final double link, final double dirty) {
        return new Config(memory, link, dirty, 250, Direction.OUTGOING, Pattern.STEADY, 0);
    }

    private static void add(final List<String> rows, final String sweep, final Config config) {
        for (final Method method : Method.values()) {
            final Metrics result = simulate(config, method);
            rows.add(String.format(Locale.US,
                "%s,%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.9f,%.9f,%.9f,%d",
                sweep, method, config.direction, config.pattern, config.memoryMb, config.linkMbps,
                config.dirtyMbS, config.meanTrafficMbps,
                config.meanTrafficMbps * (config.pattern == Pattern.BURSTY ? 2 : 1), config.phaseSeconds,
                result.totalSeconds, result.downtimeSeconds, result.transferredMb, result.rounds));
        }
    }

    static Metrics simulate(final Config config, final Method method) {
        double time = 0;
        double remaining = config.memoryMb;
        double transferred = 0;
        int rounds = 0;
        if (method != Method.POST_COPY) {
            final int limit = method == Method.PRE_COPY ? 8 : 2;
            while (rounds < limit) {
                final double end = transferEnd(config, time, remaining);
                transferred += remaining;
                remaining = Math.min(config.memoryMb, config.dirtyMbS * (end - time));
                time = end;
                rounds++;
                if (remaining <= 64) break;
            }
        }
        final double pauseStart = time;
        final double pausedMb = method == Method.PRE_COPY ? remaining : Math.min(64, remaining);
        time = transferEnd(config, time, pausedMb);
        final double downtime = time - pauseStart;
        time = transferEnd(config, time, remaining - pausedMb);
        return new Metrics(time, downtime, transferred + remaining, rounds);
    }

    /** Integrates residual capacity exactly across burst boundaries; no time-step approximation. */
    static double transferEnd(final Config config, final double start, final double amountMb) {
        if (config.pattern == Pattern.STEADY || config.direction == Direction.INCOMING || config.meanTrafficMbps == 0) {
            final double traffic = config.direction == Direction.INCOMING ? 0 : config.meanTrafficMbps;
            return start + amountMb * 8 / (config.linkMbps - traffic);
        }
        double time = start;
        double remaining = amountMb;
        while (remaining > 1e-9) {
            final double position = time + config.phaseSeconds;
            final long halfCycle = (long)Math.floor(position / 10);
            final double boundary = (halfCycle + 1) * 10.0 - config.phaseSeconds;
            final double traffic = halfCycle % 2 == 0 ? config.meanTrafficMbps * 2 : 0;
            final double bandwidth = (config.linkMbps - traffic) / 8;
            final double capacity = (boundary - time) * bandwidth;
            if (remaining <= capacity) return time + remaining / bandwidth;
            remaining -= capacity;
            time = boundary;
        }
        return time;
    }
}
