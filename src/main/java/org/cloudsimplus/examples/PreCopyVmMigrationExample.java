/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 *
 *     Copyright (C) 2015-2021 Universidade da Beira Interior (UBI, Portugal) and
 *     the Instituto Federal de Educação Ciência e Tecnologia do Tocantins (IFTO, Brazil).
 *
 *     This file is part of CloudSim Plus.
 *
 *     CloudSim Plus is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     CloudSim Plus is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with CloudSim Plus. If not, see <http://www.gnu.org/licenses/>.
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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;

/**
 * A small, event-driven model of pre-copy VM migration.
 *
 * <p>The VM continues executing during each pre-copy round. At the end of a
 * round, the model computes the memory dirtied while that round was copied.
 * Once the remaining dirty memory is below a threshold, the cloudlet is
 * paused, that remainder is copied, and the regular CloudSim Plus migration
 * completion event moves the VM to its target Host.</p>
 *
 * <p>The dirty-page rate is an input to the model. This example uses a fixed
 * rate to keep the scenario easy to understand. A workload-aware simulation
 * can replace it with a rate derived from VM or Cloudlet behavior.</p>
 */
public class PreCopyVmMigrationExample {
    private static final int HOSTS = 2;
    private static final int HOST_PES = 4;
    private static final int HOST_MIPS = 1_000;
    private static final long HOST_RAM = 16_384;
    private static final long HOST_BW = 10_000;
    private static final long HOST_STORAGE = 1_000_000;

    private static final int VM_PES = 2;
    private static final int VM_MIPS = 1_000;
    private static final long VM_RAM = 2_048;
    private static final long VM_BW = 1_000;
    private static final long VM_STORAGE = 10_000;
    private static final long CLOUDLET_LENGTH = 1_000_000;

    private static final double MIGRATION_START_TIME = 10;
    private static final double MIGRATION_BANDWIDTH_MBPS = 500;
    private static final double DIRTY_PAGE_RATE_MBPS = 50;
    private static final double STOP_AND_COPY_THRESHOLD_MB = 64;
    private static final int MAX_PRE_COPY_ROUNDS = 5;

    private PreCopyVmMigrationExample() {
        /* Utility class. */
    }

    public static void main(final String[] args) {
        final var simulation = new CloudSimPlus();
        final var hosts = createHosts();
        final Datacenter datacenter = new DatacenterSimple(simulation, hosts)
            .setSchedulingInterval(1);
        final var broker = new DatacenterBrokerSimple(simulation);
        final Vm vm = new VmSimple(VM_MIPS, VM_PES)
            .setRam(VM_RAM)
            .setBw(VM_BW)
            .setSize(VM_STORAGE);
        final Cloudlet cloudlet = new CloudletSimple(CLOUDLET_LENGTH, VM_PES);

        broker.submitVm(vm);
        broker.submitCloudlet(cloudlet);

        datacenter.addOnVmMigrationFinishListener(info ->
            System.out.printf(
                "Migration finished at %.2f: VM %d is now on Host %d%n",
                info.getTime(), info.getVm().getId(), info.getVm().getHost().getId()));

        new PreCopyMigrationController(
            simulation, datacenter, vm, cloudlet, hosts.get(1),
            MIGRATION_START_TIME, MIGRATION_BANDWIDTH_MBPS,
            DIRTY_PAGE_RATE_MBPS, STOP_AND_COPY_THRESHOLD_MB,
            MAX_PRE_COPY_ROUNDS);

        simulation.start();
    }

    private static List<Host> createHosts() {
        final List<Host> hosts = new ArrayList<>(HOSTS);
        for (int host = 0; host < HOSTS; host++) {
            final List<Pe> peList = new ArrayList<>(HOST_PES);
            for (int pe = 0; pe < HOST_PES; pe++) {
                peList.add(new PeSimple(HOST_MIPS));
            }

            hosts.add(new HostSimple(HOST_RAM, HOST_BW, HOST_STORAGE, peList));
        }

        return hosts;
    }

    /** Schedules the pre-copy rounds and final stop-and-copy operation. */
    private static final class PreCopyMigrationController extends CloudSimEntity {
        private static final int PRE_COPY_START = 30_001;
        private static final int PRE_COPY_ROUND_FINISH = 30_002;

        private final Datacenter datacenter;
        private final Vm vm;
        private final Cloudlet cloudlet;
        private final Host targetHost;
        private final double migrationStartTime;
        private final double bandwidthMbps;
        private final double dirtyPageRateMbps;
        private final double stopAndCopyThresholdMb;
        private final int maxRounds;

        private int round;
        private double copiedMemoryMb;
        private double totalMemoryMb;
        private double memoryForCurrentRoundMb;

        private PreCopyMigrationController(
            final Simulation simulation, final Datacenter datacenter,
            final Vm vm, final Cloudlet cloudlet, final Host targetHost,
            final double migrationStartTime, final double bandwidthMbps,
            final double dirtyPageRateMbps, final double stopAndCopyThresholdMb,
            final int maxRounds)
        {
            super(simulation);
            this.datacenter = datacenter;
            this.vm = vm;
            this.cloudlet = cloudlet;
            this.targetHost = targetHost;
            this.migrationStartTime = migrationStartTime;
            this.bandwidthMbps = bandwidthMbps;
            this.dirtyPageRateMbps = dirtyPageRateMbps;
            this.stopAndCopyThresholdMb = stopAndCopyThresholdMb;
            this.maxRounds = maxRounds;
        }

        @Override
        protected void startInternal() {
            schedule(migrationStartTime, PRE_COPY_START);
        }

        @Override
        public void processEvent(final SimEvent event) {
            switch (event.getTag()) {
                case PRE_COPY_START -> startMigration();
                case PRE_COPY_ROUND_FINISH -> finishPreCopyRound();
                case CloudSimTag.SIMULATION_END -> { }
                default -> throw new IllegalArgumentException(
                    "Unsupported pre-copy event tag: " + event.getTag());
            }
        }

        private void startMigration() {
            if (!vm.isCreated()) {
                throw new IllegalStateException("The VM must be created before migration starts.");
            }

            totalMemoryMb = vm.getRam().getCapacity();
            memoryForCurrentRoundMb = totalMemoryMb;
            if (!targetHost.addMigratingInVm(vm)) {
                throw new IllegalStateException("The target Host has insufficient resources for the VM.");
            }

            vm.getHost().addVmMigratingOut(vm);
            startNextPreCopyRound();
        }

        private void startNextPreCopyRound() {
            round++;
            final double duration = memoryForCurrentRoundMb / bandwidthMbps;
            System.out.printf(
                "Pre-copy round %d starts at %.2f: copying %.2f MB while the VM runs%n",
                round, getSimulation().clock(), memoryForCurrentRoundMb);
            schedule(duration, PRE_COPY_ROUND_FINISH);
        }

        private void finishPreCopyRound() {
            copiedMemoryMb += memoryForCurrentRoundMb;
            final double dirtyMemoryMb = memoryDirtiedDuringCurrentRound();
            System.out.printf(
                "Pre-copy round %d ends at %.2f: %.2f MB dirtied during the round%n",
                round, getSimulation().clock(), dirtyMemoryMb);

            if (dirtyMemoryMb <= stopAndCopyThresholdMb || round == maxRounds) {
                startStopAndCopy(dirtyMemoryMb);
                return;
            }

            memoryForCurrentRoundMb = dirtyMemoryMb;
            startNextPreCopyRound();
        }

        private double memoryDirtiedDuringCurrentRound() {
            final double duration = memoryForCurrentRoundMb / bandwidthMbps;
            return Math.min(totalMemoryMb, dirtyPageRateMbps * duration);
        }

        private void startStopAndCopy(final double dirtyMemoryMb) {
            final double downtime = dirtyMemoryMb / bandwidthMbps;
            copiedMemoryMb += dirtyMemoryMb;
            System.out.printf(
                "Stop-and-copy starts at %.2f: pausing the cloudlet and copying %.2f MB; "
                    + "estimated downtime %.3f s%n",
                getSimulation().clock(), dirtyMemoryMb, downtime);

            datacenter.schedule(datacenter, 0, CloudSimTag.CLOUDLET_PAUSE, cloudlet);
            datacenter.schedule(
                datacenter, downtime, CloudSimTag.VM_MIGRATE,
                new AbstractMap.SimpleEntry<>(vm, targetHost));
            datacenter.schedule(
                datacenter, downtime + getSimulation().getMinTimeBetweenEvents(),
                CloudSimTag.CLOUDLET_RESUME, cloudlet);

            System.out.printf(
                "Pre-copy transfers %.2f MB in %d rounds; migration completes at %.2f%n",
                copiedMemoryMb, round, getSimulation().clock() + downtime);
        }
    }
}
