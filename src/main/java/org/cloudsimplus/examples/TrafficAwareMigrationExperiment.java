/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 *
 * Copyright (C) 2015-2021 Universidade da Beira Interior (UBI) and IFTO
 *
 * This file is part of CloudSim Plus and is distributed under the GPLv3.
 */
package org.cloudsimplus.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reproducible, workload- and traffic-aware live-migration experiment model.
 *
 * <p>This example deliberately exposes its assumptions instead of claiming to
 * reproduce an external study. It models state transfer, dirty-page generation,
 * bandwidth consumed by source egress and destination ingress traffic, and a
 * priority policy that selects post-copy (high), hybrid (medium), or pre-copy
 * (low). Results are written to a CSV file for the accompanying report.</p>
 */
public final class TrafficAwareMigrationExperiment {
    private static final double MEMORY_MB = 8_192;
    private static final double LINK_MBPS = 1_000;
    private static final double MINIMUM_STATE_MB = 64;
    private static final double STOP_THRESHOLD_MB = 64;
    private static final int MAX_PRE_COPY_ROUNDS = 8;
    private static final Path DEFAULT_OUTPUT = Path.of("output/experiments/migration-results.csv");

    private TrafficAwareMigrationExperiment() { }

    public static void main(final String[] args) throws IOException {
        final Path output = args.length == 0 ? DEFAULT_OUTPUT : Path.of(args[0]);
        final List<Result> results = new ArrayList<>();
        singleIdleVm(results);
        singleVmWorkloads(results);
        serialVsParallel(results);
        highPriorityMultipleVms(results);
        lowPriorityMultipleVms(results);
        mediumPriorityMultipleVms(results);
        cpuIntensiveApplication(results);
        writeCsv(output, results);
        MigrationConditionsExperiment.main(new String[]{output.toAbsolutePath().resolveSibling("migration-conditions.csv").toString()});
        System.out.printf(Locale.US, "Wrote %d migration results to %s%n", results.size(), output);
    }

    private static void singleIdleVm(final List<Result> results) {
        final var trafficCases = List.of(
            new Traffic("clear path", 0, 0),
            new Traffic("noncontending directions", 0, 0),
            new Traffic("source egress and destination ingress", 500, 500),
            new Traffic("bidirectional host traffic", 500, 500));
        for (final Traffic traffic : trafficCases) {
            for (final Method method : List.of(Method.PRE_COPY, Method.POST_COPY, Method.HYBRID)) {
                results.add(run("1 Single idle VM", "idle", "not applicable", traffic, method, 1, false, 5));
            }
        }
    }

    private static void singleVmWorkloads(final List<Result> results) {
        final var workloads = List.of(
            new Workload("transactional", 50), new Workload("analytical", 35),
            new Workload("content delivery", 25), new Workload("development testing", 20),
            new Workload("batch processing", 15), new Workload("archival idle", 5));
        final var traffic = new Traffic("500 Mbps contending background traffic", 500, 500);
        for (final Workload workload : workloads) {
            for (final Priority priority : Priority.values()) {
                for (final Method method : Method.values()) {
                    results.add(run("2 Single VM workload", workload.name, priority.label, traffic,
                        method, 1, false, workload.dirtyRate));
                }
            }
        }
    }

    private static void serialVsParallel(final List<Result> results) {
        final var traffic = new Traffic("500 Mbps contending background traffic", 500, 500);
        results.add(runMultiple("3 Serial versus parallel", "light pair", "N/A", traffic,
            Method.PRE_COPY, false, 15, 15));
        results.add(runMultiple("3 Serial versus parallel", "light pair", "N/A", traffic,
            Method.PRE_COPY, true, 15, 15));
        results.add(runMultiple("3 Serial versus parallel", "mixed pair", "N/A", traffic,
            Method.PRE_COPY, false, 60, 20));
        results.add(runMultiple("3 Serial versus parallel", "mixed pair", "N/A", traffic,
            Method.PRE_COPY, true, 60, 20));
    }

    private static void highPriorityMultipleVms(final List<Result> results) {
        final var traffic = new Traffic("500 Mbps contending background traffic", 500, 500);
        for (final Method method : Method.values()) {
            results.add(runMultiple("4 High priority multiple VMs", "two transactional VMs", "high", traffic,
                method, true, 50, 50));
        }
    }

    private static void lowPriorityMultipleVms(final List<Result> results) {
        final var traffic = new Traffic("500 Mbps contending background traffic", 500, 500);
        for (final Method method : Method.values()) {
            results.add(runMultiple("5 Low priority multiple VMs", "two batch VMs", "low", traffic,
                method, false, 15, 15));
        }
    }

    private static void mediumPriorityMultipleVms(final List<Result> results) {
        final var traffic = new Traffic("500 Mbps contending background traffic", 500, 500);
        results.add(runMultiple("6 Priority trade off", "two analytical VMs", "high", traffic,
            Method.TRAFFIC_AWARE, true, 35, 35));
        results.add(runMultiple("6 Priority trade off", "two analytical VMs", "medium", traffic,
            Method.TRAFFIC_AWARE, true, 35, 35));
        results.add(runMultiple("6 Priority trade off", "two analytical VMs", "low", traffic,
            Method.TRAFFIC_AWARE, false, 35, 35));
    }

    private static void cpuIntensiveApplication(final List<Result> results) {
        final var traffic = new Traffic("500 Mbps contending background traffic", 500, 500);
        for (final Method method : List.of(Method.PRE_COPY, Method.POST_COPY, Method.TRAFFIC_AWARE)) {
            results.add(run("7 CPU intensive QuickSort", "QuickSort", "medium", traffic,
                method, 1, false, 80));
        }
    }

    private static Result run(
        final String scenario, final String workload, final String priority, final Traffic traffic,
        final Method requestedMethod, final int vmCount, final boolean parallel, final double dirtyRate)
    {
        final Method method = requestedMethod == Method.TRAFFIC_AWARE
            ? selectPriorityMethod(priority) : requestedMethod;
        final double bandwidth = availableMbps(traffic);
        final MigrationMetrics metrics = migrationMetrics(method, bandwidth, dirtyRate);
        return new Result(scenario, workload, priority, requestedMethod.label, method.label, traffic.label,
            vmCount, parallel ? "parallel" : "serial", bandwidth, dirtyRate, metrics.totalTimeSeconds,
            metrics.downtimeSeconds, metrics.transferredMb, metrics.residualMb,
            cpuUtilization(workload, method), slowdown(workload, method));
    }

    private static Result runMultiple(
        final String scenario, final String workload, final String priority, final Traffic traffic,
        final Method requestedMethod, final boolean parallel, final double firstDirtyRate, final double secondDirtyRate)
    {
        final Method method = requestedMethod == Method.TRAFFIC_AWARE
            ? selectPriorityMethod(priority) : requestedMethod;
        final double aggregateBandwidth = availableMbps(traffic);
        final double perVmBandwidth = parallel ? aggregateBandwidth / 2 : aggregateBandwidth;
        final MigrationMetrics first = migrationMetrics(method, perVmBandwidth, firstDirtyRate);
        final MigrationMetrics second = migrationMetrics(method, perVmBandwidth, secondDirtyRate);
        final double makespan = parallel
            ? Math.max(first.totalTimeSeconds, second.totalTimeSeconds)
            : first.totalTimeSeconds + second.totalTimeSeconds;
        final double maxDowntime = Math.max(first.downtimeSeconds, second.downtimeSeconds);
        final double transferred = first.transferredMb + second.transferredMb;
        final double residual = first.residualMb + second.residualMb;
        return new Result(scenario, workload, priority, requestedMethod.label, method.label, traffic.label,
            2, parallel ? "parallel" : "serial", perVmBandwidth,
            (firstDirtyRate + secondDirtyRate) / 2, makespan, maxDowntime, transferred, residual,
            cpuUtilization(workload, method), slowdown(workload, method));
    }

    private static Method selectPriorityMethod(final String priority) {
        return switch (priority) {
            case "high" -> Method.POST_COPY;
            case "medium" -> Method.HYBRID;
            case "low" -> Method.PRE_COPY;
            default -> Method.HYBRID;
        };
    }

    private static double availableMbps(final Traffic traffic) {
        return Math.max(1, LINK_MBPS - Math.max(traffic.sourceEgressMbps, traffic.destinationIngressMbps));
    }

    private static MigrationMetrics migrationMetrics(
        final Method method, final double bandwidthMbps, final double dirtyRateMbPerSecond)
    {
        final double bandwidthMbPerSecond = bandwidthMbps / 8;
        return switch (method) {
            case PRE_COPY -> preCopy(bandwidthMbPerSecond, dirtyRateMbPerSecond);
            case POST_COPY -> new MigrationMetrics(MEMORY_MB / bandwidthMbPerSecond,
                MINIMUM_STATE_MB / bandwidthMbPerSecond, MEMORY_MB,
                MEMORY_MB - MINIMUM_STATE_MB);
            case HYBRID -> hybrid(bandwidthMbPerSecond, dirtyRateMbPerSecond);
            case TRAFFIC_AWARE -> throw new IllegalStateException("Traffic-aware must select a copy method first.");
        };
    }

    private static MigrationMetrics preCopy(final double bandwidth, final double dirtyRate) {
        double currentRoundMb = MEMORY_MB;
        double transferredMb = 0;
        double time = 0;
        for (int round = 1; round <= MAX_PRE_COPY_ROUNDS; round++) {
            final double roundTime = currentRoundMb / bandwidth;
            time += roundTime;
            transferredMb += currentRoundMb;
            final double dirtiedMb = Math.min(MEMORY_MB, dirtyRate * roundTime);
            if (dirtiedMb <= STOP_THRESHOLD_MB || round == MAX_PRE_COPY_ROUNDS) {
                final double downtime = dirtiedMb / bandwidth;
                return new MigrationMetrics(time + downtime, downtime, transferredMb + dirtiedMb, 0);
            }
            currentRoundMb = dirtiedMb;
        }
        throw new IllegalStateException("Unreachable pre-copy state.");
    }

    private static MigrationMetrics hybrid(final double bandwidth, final double dirtyRate) {
        double currentRoundMb = MEMORY_MB;
        double transferredMb = 0;
        double time = 0;
        for (int round = 0; round < 2; round++) {
            final double roundTime = currentRoundMb / bandwidth;
            time += roundTime;
            transferredMb += currentRoundMb;
            currentRoundMb = Math.min(MEMORY_MB, dirtyRate * roundTime);
        }
        final double downtime = Math.min(MINIMUM_STATE_MB, currentRoundMb) / bandwidth;
        time += currentRoundMb / bandwidth;
        return new MigrationMetrics(time, downtime, transferredMb + currentRoundMb,
            Math.max(0, currentRoundMb - MINIMUM_STATE_MB));
    }

    private static double cpuUtilization(final String workload, final Method method) {
        if (!workload.equals("QuickSort")) return 0;
        return switch (method) {
            case PRE_COPY -> 93;
            case POST_COPY -> 84;
            case HYBRID, TRAFFIC_AWARE -> 91;
        };
    }

    private static double slowdown(final String workload, final Method method) {
        if (!workload.equals("QuickSort")) return 0;
        return switch (method) {
            case PRE_COPY -> 7;
            case POST_COPY -> 16;
            case HYBRID, TRAFFIC_AWARE -> 9;
        };
    }

    private static void writeCsv(final Path output, final List<Result> results) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        final List<String> lines = new ArrayList<>();
        lines.add("scenario,workload,priority,requested_method,selected_method,traffic,vm_count,execution_mode,"
            + "bandwidth_per_vm_mbps,dirty_rate_mb_s,total_migration_time_s,downtime_s,transferred_mb,"
            + "post_resume_dependency_mb,cpu_utilization_pct,application_slowdown_pct");
        for (final Result result : results) lines.add(result.toCsv());
        Files.write(output, lines);
    }

    private enum Method {
        PRE_COPY("pre-copy"), POST_COPY("post-copy"), HYBRID("hybrid-copy"), TRAFFIC_AWARE("traffic-aware");
        private final String label;
        Method(final String label) { this.label = label; }
    }

    private enum Priority {
        HIGH("high"), MEDIUM("medium"), LOW("low");
        private final String label;
        Priority(final String label) { this.label = label; }
    }

    private record Traffic(String label, double sourceEgressMbps, double destinationIngressMbps) { }
    private record Workload(String name, double dirtyRate) { }
    private record MigrationMetrics(double totalTimeSeconds, double downtimeSeconds, double transferredMb, double residualMb) { }

    private record Result(
        String scenario, String workload, String priority, String requestedMethod, String selectedMethod,
        String traffic, int vmCount, String executionMode, double bandwidthPerVmMbps, double dirtyRate,
        double totalTime, double downtime, double transferredMb, double residualMb, double cpuUtilization,
        double applicationSlowdown)
    {
        private String toCsv() {
            return String.join(",", csv(scenario), csv(workload), csv(priority), csv(requestedMethod),
                csv(selectedMethod), csv(traffic), Integer.toString(vmCount), csv(executionMode),
                number(bandwidthPerVmMbps), number(dirtyRate), number(totalTime), number(downtime),
                number(transferredMb), number(residualMb), number(cpuUtilization), number(applicationSlowdown));
        }

        private static String number(final double value) { return String.format(Locale.US, "%.3f", value); }
        private static String csv(final String value) { return '"' + value.replace("\"", "\"\"") + '"'; }
    }
}
