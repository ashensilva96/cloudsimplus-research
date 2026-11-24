package org.cloudsimplus.examples;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimpleVmMigration {
    private static final int HOSTS = 2;
    private static final int HOST_PES = 4;
    private static final int HOST_MIPS = 1_000;
    private static final long HOST_RAM = 16_384;      // MB
    private static final long HOST_BW = 10_000;       // Mbps
    private static final long HOST_STORAGE = 1_000_000; // MB

    private static final int VM_PES = 2;
    private static final int VM_MIPS = 1_000;
    private static final long VM_RAM = 2_048;         // MB
    private static final long VM_BW = 1_000;          // Mbps
    private static final long VM_STORAGE = 10_000;    // MB

    private static final double MIGRATION_TIME = 10.0;
    private static final long CLOUDLET_LENGTH = 1_000_000;

    public static void main(String[] args) {
        final CloudSimPlus simulation = new CloudSimPlus();

        final List<Host> hosts = createHosts();
        final Datacenter datacenter = new DatacenterSimple(simulation, hosts);
        datacenter.setSchedulingInterval(1);
        datacenter.setBandwidthPercentForMigration(0.5);

        final DatacenterBrokerSimple broker =
            new DatacenterBrokerSimple(simulation);

        final Vm vm = new VmSimple(VM_MIPS, VM_PES)
            .setRam(VM_RAM)
            .setBw(VM_BW)
            .setSize(VM_STORAGE);

        final CloudletSimple cloudlet =
            new CloudletSimple(CLOUDLET_LENGTH, VM_PES);

        broker.submitVm(vm);
        broker.submitCloudlet(cloudlet);

        vm.addOnMigrationStartListener(info ->
            System.out.printf(
                "Migration started at %.2f: VM %d moves from Host %d to Host %d%n",
                info.getTime(),
                info.getVm().getId(),
                info.getVm().getHost().getId(),
                info.getHost().getId()
            )
        );

        vm.addOnMigrationFinishListener(info ->
            System.out.printf(
                "Migration finished at %.2f: VM %d is now on Host %d%n",
                info.getTime(),
                info.getVm().getId(),
                info.getHost().getId()
            )
        );

        final AtomicBoolean migrationRequested = new AtomicBoolean(false);

        simulation.addOnClockTickListener(event -> {
            if (!migrationRequested.get()
                && event.getTime() >= MIGRATION_TIME
                && vm.isCreated()) {

                final Host sourceHost = vm.getHost();

                final Host targetHost = hosts.stream()
                    .filter(host -> !host.equals(sourceHost))
                    .findFirst()
                    .orElseThrow();

                migrationRequested.set(true);

                System.out.printf(
                    "Requesting migration at %.2f: VM %d from Host %d to Host %d%n",
                    event.getTime(),
                    vm.getId(),
                    sourceHost.getId(),
                    targetHost.getId()
                );

                datacenter.requestVmMigration(vm, targetHost);
            }
        });

        simulation.start();

        System.out.printf(
            "Simulation finished. VM %d final host: Host %d%n",
            vm.getId(),
            vm.getHost().getId()
        );
    }

    private static List<Host> createHosts() {
        final List<Host> hosts = new ArrayList<>(HOSTS);

        for (int i = 0; i < HOSTS; i++) {
            final List<Pe> peList = new ArrayList<>(HOST_PES);

            for (int pe = 0; pe < HOST_PES; pe++) {
                peList.add(new PeSimple(HOST_MIPS));
            }

            hosts.add(
                new HostSimple(HOST_RAM, HOST_BW, HOST_STORAGE, peList)
            );
        }

        return hosts;
    }
}
