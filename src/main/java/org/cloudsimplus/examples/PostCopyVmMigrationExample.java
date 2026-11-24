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
 * A small, event-driven model of post-copy VM migration.
 *
 * <p>The model pauses the VM only long enough to transfer its minimum execution
 * state. It then moves the VM to the target Host and resumes its cloudlet.
 * The remaining memory is fetched while the VM continues running. Unlike
 * pre-copy, post-copy keeps the downtime small but leaves the VM dependent on
 * memory transfers after it resumes.</p>
 *
 * <p>The amount of minimum state and the migration bandwidth are inputs to this
 * model. A workload-aware simulation can replace them with values measured from
 * its VM or Cloudlet behavior.</p>
 */
public class PostCopyVmMigrationExample {
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
    private static final double MINIMUM_EXECUTION_STATE_MB = 64;

    private PostCopyVmMigrationExample() {
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

        new PostCopyMigrationController(
            simulation, datacenter, vm, cloudlet, hosts.get(1),
            MIGRATION_START_TIME, MIGRATION_BANDWIDTH_MBPS,
            MINIMUM_EXECUTION_STATE_MB);

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

    /** Schedules the stop-and-copy, VM resume, and remaining-memory transfer. */
    private static final class PostCopyMigrationController extends CloudSimEntity {
        private static final int POST_COPY_START = 30_101;
        private static final int MINIMUM_STATE_TRANSFER_FINISH = 30_102;
        private static final int REMAINING_MEMORY_TRANSFER_FINISH = 30_103;

        private final Datacenter datacenter;
        private final Vm vm;
        private final Cloudlet cloudlet;
        private final Host targetHost;
        private final double migrationStartTime;
        private final double bandwidthMbps;
        private final double minimumExecutionStateMb;

        private double minimumStateMb;
        private double remainingMemoryMb;

        private PostCopyMigrationController(
            final Simulation simulation, final Datacenter datacenter,
            final Vm vm, final Cloudlet cloudlet, final Host targetHost,
            final double migrationStartTime, final double bandwidthMbps,
            final double minimumExecutionStateMb)
        {
            super(simulation);
            this.datacenter = datacenter;
            this.vm = vm;
            this.cloudlet = cloudlet;
            this.targetHost = targetHost;
            this.migrationStartTime = migrationStartTime;
            this.bandwidthMbps = bandwidthMbps;
            this.minimumExecutionStateMb = minimumExecutionStateMb;
        }

        @Override
        protected void startInternal() {
            schedule(migrationStartTime, POST_COPY_START);
        }

        @Override
        public void processEvent(final SimEvent event) {
            switch (event.getTag()) {
                case POST_COPY_START -> startMigration();
                case MINIMUM_STATE_TRANSFER_FINISH -> finishMinimumStateTransfer();
                case REMAINING_MEMORY_TRANSFER_FINISH -> finishRemainingMemoryTransfer();
                case CloudSimTag.SIMULATION_END -> { }
                default -> throw new IllegalArgumentException(
                    "Unsupported post-copy event tag: " + event.getTag());
            }
        }

        private void startMigration() {
            if (!vm.isCreated()) {
                throw new IllegalStateException("The VM must be created before migration starts.");
            }

            final double totalMemoryMb = vm.getRam().getCapacity();
            minimumStateMb = Math.min(minimumExecutionStateMb, totalMemoryMb);
            remainingMemoryMb = totalMemoryMb - minimumStateMb;
            if (!targetHost.addMigratingInVm(vm)) {
                throw new IllegalStateException("The target Host has insufficient resources for the VM.");
            }

            vm.getHost().addVmMigratingOut(vm);
            final double downtime = minimumStateMb / bandwidthMbps;
            System.out.printf(
                "Post-copy starts at %.2f: pausing the cloudlet and copying %.2f MB; "
                    + "estimated downtime %.3f s%n",
                getSimulation().clock(), minimumStateMb, downtime);
            datacenter.schedule(datacenter, 0, CloudSimTag.CLOUDLET_PAUSE, cloudlet);
            schedule(downtime, MINIMUM_STATE_TRANSFER_FINISH);
        }

        private void finishMinimumStateTransfer() {
            final double remainingTransferTime = remainingMemoryMb / bandwidthMbps;
            System.out.printf(
                "Minimum state transferred at %.2f: moving VM to Host %d and resuming the cloudlet; "
                    + "%.2f MB remains to be fetched%n",
                getSimulation().clock(), targetHost.getId(), remainingMemoryMb);

            datacenter.schedule(
                datacenter, 0, CloudSimTag.VM_MIGRATE,
                new AbstractMap.SimpleEntry<>(vm, targetHost));
            datacenter.schedule(
                datacenter, getSimulation().getMinTimeBetweenEvents(),
                CloudSimTag.CLOUDLET_RESUME, cloudlet);
            schedule(remainingTransferTime, REMAINING_MEMORY_TRANSFER_FINISH);
        }

        private void finishRemainingMemoryTransfer() {
            System.out.printf(
                "Post-copy memory transfer completed at %.2f: %.2f MB fetched while the VM ran%n",
                getSimulation().clock(), remainingMemoryMb);
        }
    }
}
