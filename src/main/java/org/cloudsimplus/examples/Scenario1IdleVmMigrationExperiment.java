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

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimEntity;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.core.CloudSimTag;
import org.cloudsimplus.core.Simulation;
import org.cloudsimplus.core.events.SimEvent;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scenario 1: event-driven CloudSim Plus simulation of an idle 8 GB VM migration.
 *
 * <p>Each of the twelve runs creates a new {@link CloudSimPlus} simulation,
 * {@link DatacenterSimple}, source and destination {@link HostSimple}s,
 * {@link VmSimple}, and an idle {@link CloudletSimple}. Pre-copy, post-copy,
 * and hybrid-copy are represented by CloudSim events that pause/resume the
 * cloudlet and complete the actual VM relocation by sending {@code VM_MIGRATE}
 * to the datacenter.</p>
 *
 * <p>CloudSim Plus' core migration API reserves a configurable percentage of
 * the target Host bandwidth; it does not independently model directional NIC
 * queues. The four traffic combinations are therefore represented by that
 * reservation: source egress and destination ingress traffic reduce the
 * migration reservation, while traffic in the opposite directions does not.
 * The transfer, pause, resume, and VM relocation timestamps are all measured
 * from the CloudSim event clock.</p>
 */
public final class Scenario1IdleVmMigrationExperiment {
    private static final Path OUTPUT = Path.of("output/experiments/scenario-1-idle-vm-results.csv");
    private static final int HOST_PES = 4;
    private static final long HOST_MIPS = 10_000;
    private static final long HOST_RAM_MB = 16_384;
    private static final long HOST_BW_MBPS = 1_000;
    private static final long HOST_STORAGE_MB = 1_000_000;
    private static final int VM_PES = 2;
    private static final long VM_MIPS = 2_000;
    private static final long VM_RAM_MB = 8_192;
    private static final long VM_BW_MBPS = 1;
    private static final long VM_STORAGE_MB = 10_000;
    private static final long CLOUDLET_LENGTH = 1_000_000_000L;
    private static final double START_TIME_SECONDS = 10;
    private static final double MINIMUM_STATE_MB = 64;
    private static final double PRE_COPY_STOP_THRESHOLD_MB = 64;
    private static final int MAX_PRE_COPY_ROUNDS = 8;
    private static final double IDLE_DIRTY_RATE_MBPS = 5;

    private Scenario1IdleVmMigrationExperiment() { }

    public static void main(final String[] args) throws IOException {
        final List<Result> results = new ArrayList<>();
        for (final TrafficPattern traffic : TrafficPattern.values()) {
            for (final Technique technique : Technique.values()) {
                results.add(run(traffic, technique));
            }
        }
        writeResults(results);
        printSummary(results);
    }

    private static Result run(final TrafficPattern traffic, final Technique technique) {
        final var simulation = new CloudSimPlus();
        final var hosts = createHosts();
        final Datacenter datacenter = new DatacenterSimple(simulation, hosts).setSchedulingInterval(0.1);
        datacenter.setBandwidthPercentForMigration(traffic.migrationBandwidthPercent());

        final var broker = new DatacenterBrokerSimple(simulation);
        final Vm vm = new VmSimple(VM_MIPS, VM_PES)
            .setRam(VM_RAM_MB)
            .setBw(VM_BW_MBPS)
            .setSize(VM_STORAGE_MB);
        final Cloudlet cloudlet = new CloudletSimple(CLOUDLET_LENGTH, VM_PES);
        broker.submitVm(vm);
        broker.submitCloudlet(cloudlet);

        final var controller = new MigrationController(
            simulation, datacenter, vm, cloudlet, hosts.get(1), traffic, technique);
        simulation.start();
        return controller.result();
    }

    private static List<Host> createHosts() {
        final List<Host> hosts = new ArrayList<>(2);
        for (int hostIndex = 0; hostIndex < 2; hostIndex++) {
            final List<Pe> peList = new ArrayList<>(HOST_PES);
            for (int peIndex = 0; peIndex < HOST_PES; peIndex++) {
                peList.add(new PeSimple(HOST_MIPS));
            }
            hosts.add(new HostSimple(HOST_RAM_MB, HOST_BW_MBPS, HOST_STORAGE_MB, peList));
        }
        return hosts;
    }

    private static void writeResults(final List<Result> results) throws IOException {
        Files.createDirectories(OUTPUT.getParent());
        final List<String> lines = new ArrayList<>();
        lines.add("traffic_pattern,technique,configured_migration_bandwidth_mbps,precopy_rounds,"
            + "migration_start_s,vm_relocated_s,full_transfer_complete_s,total_migration_time_s,"
            + "pause_start_s,resume_s,measured_downtime_s,post_resume_dependency_mb");
        for (final Result result : results) {
            lines.add(result.toCsv());
        }
        Files.write(OUTPUT, lines);
    }

    private static void printSummary(final List<Result> results) {
        System.out.println("Scenario 1 - idle 8 GB VM migration");
        System.out.println("traffic | technique | bandwidth Mbps | migration s | downtime s | post-resume MB");
        for (final Result result : results) {
            System.out.printf(Locale.US, "%s | %s | %.0f | %.3f | %.3f | %.3f%n",
                result.traffic.label, result.technique.label, result.bandwidthMbps,
                result.totalMigrationTime, result.measuredDowntime, result.postResumeDependencyMb);
        }
        System.out.println("Detailed results: " + OUTPUT);
    }

    private enum Technique {
        PRE_COPY("pre-copy"),
        POST_COPY("post-copy"),
        HYBRID_COPY("hybrid-copy");

        private final String label;

        Technique(final String label) {
            this.label = label;
        }
    }

    /** Traffic directions are relative to the migration path: source to destination. */
    private enum TrafficPattern {
        NO_BACKGROUND("no background traffic", 0, 0, 0, 0),
        OPPOSITE_DIRECTIONS("source ingress and destination egress", 500, 0, 0, 500),
        CONTENDING_DIRECTIONS("source egress and destination ingress", 0, 500, 500, 0),
        BIDIRECTIONAL("500 Mbps in both directions at both hosts", 500, 500, 500, 500);

        private final String label;
        private final double sourceIngressMbps;
        private final double sourceEgressMbps;
        private final double destinationIngressMbps;
        private final double destinationEgressMbps;

        TrafficPattern(
            final String label, final double sourceIngressMbps, final double sourceEgressMbps,
            final double destinationIngressMbps, final double destinationEgressMbps)
        {
            this.label = label;
            this.sourceIngressMbps = sourceIngressMbps;
            this.sourceEgressMbps = sourceEgressMbps;
            this.destinationIngressMbps = destinationIngressMbps;
            this.destinationEgressMbps = destinationEgressMbps;
        }

        private double availableMigrationBandwidthMbps() {
            return HOST_BW_MBPS - Math.max(sourceEgressMbps, destinationIngressMbps);
        }

        private double migrationBandwidthPercent() {
            return availableMigrationBandwidthMbps() / HOST_BW_MBPS;
        }
    }

    private static final class MigrationController extends CloudSimEntity {
        private static final int START = 40_001;
        private static final int PRE_COPY_ROUND_DONE = 40_002;
        private static final int POST_COPY_MINIMUM_STATE_DONE = 40_003;
        private static final int HYBRID_PRE_COPY_ROUND_DONE = 40_004;
        private static final int FULL_TRANSFER_DONE = 40_005;

        private final Datacenter datacenter;
        private final Vm vm;
        private final Cloudlet cloudlet;
        private final Host targetHost;
        private final TrafficPattern traffic;
        private final Technique technique;
        private final double migrationBandwidthMbps;

        private int preCopyRounds;
        private double currentRoundMb;
        private double migrationStart;
        private double pauseStart;
        private double resumeTime;
        private double vmRelocatedTime;
        private double fullTransferCompleteTime;
        private double postResumeDependencyMb;

        private MigrationController(
            final Simulation simulation, final Datacenter datacenter, final Vm vm,
            final Cloudlet cloudlet, final Host targetHost, final TrafficPattern traffic,
            final Technique technique)
        {
            super(simulation);
            this.datacenter = datacenter;
            this.vm = vm;
            this.cloudlet = cloudlet;
            this.targetHost = targetHost;
            this.traffic = traffic;
            this.technique = technique;
            this.migrationBandwidthMbps = traffic.availableMigrationBandwidthMbps();
            vm.addOnMigrationFinishListener(info -> vmRelocatedTime = info.getTime());
        }

        @Override
        protected void startInternal() {
            schedule(START_TIME_SECONDS, START);
        }

        @Override
        public void processEvent(final SimEvent event) {
            switch (event.getTag()) {
                case START -> startMigration();
                case PRE_COPY_ROUND_DONE -> completePreCopyRound();
                case POST_COPY_MINIMUM_STATE_DONE -> completePostCopyMinimumState();
                case HYBRID_PRE_COPY_ROUND_DONE -> completeHybridPreCopyRound();
                case FULL_TRANSFER_DONE -> finishExperiment();
                case CloudSimTag.SIMULATION_END -> { }
                default -> throw new IllegalArgumentException("Unsupported migration event: " + event.getTag());
            }
        }

        private void startMigration() {
            if (!vm.isCreated()) {
                schedule(getSimulation().getMinTimeBetweenEvents(), START);
                return;
            }
            if (!targetHost.addMigratingInVm(vm)) {
                throw new IllegalStateException("The destination Host cannot reserve the migrating VM.");
            }
            vm.getHost().addVmMigratingOut(vm);
            migrationStart = getSimulation().clock();
            // Keep the migration state explicit. VM resource allocation may be
            // dynamically resized by a policy, whereas this scenario fixes the
            // memory image at the requested 8,192 MB for every copy method.
            currentRoundMb = VM_RAM_MB;
            switch (technique) {
                case PRE_COPY -> startPreCopyRound();
                case POST_COPY -> startPostCopy();
                case HYBRID_COPY -> startHybridPreCopyRound();
            }
        }

        private void startPreCopyRound() {
            preCopyRounds++;
            schedule(secondsToTransfer(currentRoundMb), PRE_COPY_ROUND_DONE);
        }

        private void completePreCopyRound() {
            final double dirtiedMb = Math.min(VM_RAM_MB,
                IDLE_DIRTY_RATE_MBPS * secondsToTransfer(currentRoundMb));
            if (dirtiedMb <= PRE_COPY_STOP_THRESHOLD_MB || preCopyRounds == MAX_PRE_COPY_ROUNDS) {
                pauseAndMove(dirtiedMb, 0);
                return;
            }
            currentRoundMb = dirtiedMb;
            startPreCopyRound();
        }

        private void startPostCopy() {
            pauseStart = getSimulation().clock();
            datacenter.schedule(datacenter, 0, CloudSimTag.CLOUDLET_PAUSE, cloudlet);
            schedule(secondsToTransfer(MINIMUM_STATE_MB), POST_COPY_MINIMUM_STATE_DONE);
        }

        private void completePostCopyMinimumState() {
            moveVmAndResume();
            postResumeDependencyMb = VM_RAM_MB - MINIMUM_STATE_MB;
            schedule(secondsToTransfer(postResumeDependencyMb), FULL_TRANSFER_DONE);
        }

        private void startHybridPreCopyRound() {
            preCopyRounds++;
            schedule(secondsToTransfer(currentRoundMb), HYBRID_PRE_COPY_ROUND_DONE);
        }

        private void completeHybridPreCopyRound() {
            currentRoundMb = Math.min(VM_RAM_MB,
                IDLE_DIRTY_RATE_MBPS * secondsToTransfer(currentRoundMb));
            if (preCopyRounds == 2) {
                final double minimumState = Math.min(MINIMUM_STATE_MB, currentRoundMb);
                pauseAndMove(minimumState, currentRoundMb - minimumState);
                return;
            }
            startHybridPreCopyRound();
        }

        private void pauseAndMove(final double pausedTransferMb, final double remainingAfterResumeMb) {
            pauseStart = getSimulation().clock();
            datacenter.schedule(datacenter, 0, CloudSimTag.CLOUDLET_PAUSE, cloudlet);
            datacenter.schedule(datacenter, secondsToTransfer(pausedTransferMb), CloudSimTag.VM_MIGRATE,
                new AbstractMap.SimpleEntry<>(vm, targetHost));
            resumeTime = getSimulation().clock() + secondsToTransfer(pausedTransferMb)
                + getSimulation().getMinTimeBetweenEvents();
            datacenter.schedule(datacenter, secondsToTransfer(pausedTransferMb)
                + getSimulation().getMinTimeBetweenEvents(), CloudSimTag.CLOUDLET_RESUME, cloudlet);
            postResumeDependencyMb = remainingAfterResumeMb;
            schedule(secondsToTransfer(pausedTransferMb) + secondsToTransfer(remainingAfterResumeMb)
                + getSimulation().getMinTimeBetweenEvents(), FULL_TRANSFER_DONE);
        }

        private void moveVmAndResume() {
            datacenter.schedule(datacenter, 0, CloudSimTag.VM_MIGRATE,
                new AbstractMap.SimpleEntry<>(vm, targetHost));
            resumeTime = getSimulation().clock() + getSimulation().getMinTimeBetweenEvents();
            datacenter.schedule(datacenter, getSimulation().getMinTimeBetweenEvents(),
                CloudSimTag.CLOUDLET_RESUME, cloudlet);
        }

        private void finishExperiment() {
            fullTransferCompleteTime = getSimulation().clock();
            /*
             * Let the broker receive the Cloudlet return and destroy the VM
             * while the simulation event queue is still active. Calling
             * Simulation.terminate() here would make the broker's normal
             * cleanup attempt send an event after shutdown.
             */
            datacenter.schedule(datacenter, 0, CloudSimTag.CLOUDLET_CANCEL, cloudlet);
        }

        private double secondsToTransfer(final double memoryMb) {
            return memoryMb * 8 / migrationBandwidthMbps;
        }

        private Result result() {
            if (fullTransferCompleteTime == 0 || vmRelocatedTime == 0) {
                throw new IllegalStateException("The migration did not complete.");
            }
            return new Result(traffic, technique, migrationBandwidthMbps, preCopyRounds, migrationStart,
                vmRelocatedTime, fullTransferCompleteTime, fullTransferCompleteTime - migrationStart,
                pauseStart, resumeTime, resumeTime - pauseStart, postResumeDependencyMb);
        }
    }

    private record Result(
        TrafficPattern traffic, Technique technique, double bandwidthMbps, int preCopyRounds,
        double migrationStart, double vmRelocated, double fullTransferComplete,
        double totalMigrationTime, double pauseStart, double resumeTime, double measuredDowntime,
        double postResumeDependencyMb)
    {
        private String toCsv() {
            return String.format(Locale.US,
                "\"%s\",\"%s\",%.3f,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",
                traffic.label, technique.label, bandwidthMbps, preCopyRounds, migrationStart, vmRelocated,
                fullTransferComplete, totalMigrationTime, pauseStart, resumeTime, measuredDowntime,
                postResumeDependencyMb);
        }
    }
}
