# Configurable VM network traffic example

This example implements the supervisor's requirement as a **synthetic simulation
workload**: incoming, outgoing, or simultaneous traffic to/from a target VM,
with independently adjustable traffic intensity.

CloudSim Plus creates two VMs on different hosts and runs the event clock. An
example-specific `CloudSimEntity` generates data batches and schedules their
delivery over two explicitly modeled, lossless FIFO links. There are no operating
systems, sockets, real packets, or executable iperf processes in this experiment.
It is a small reproducible workload generator, not an established external benchmark.

```text
Host 0: target VM 0                   Host 1: peer VM 1
                   outgoing ------>
                   <------ incoming
```

Each direction has its own fixed capacity (full duplex). A 1,000 Mbps capacity
means up to 1,000 Mbps incoming AND 1,000 Mbps outgoing simultaneously. VM 0 is
always the target; the direction names are relative to it.

## Run

Use JDK 25 and Maven from the repository root. On macOS, if Maven selects a
different installed JDK, first run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
```

Run the default suite:

```bash
mvn -q -DskipTests compile org.codehaus.mojo:exec-maven-plugin:3.6.3:java \
  -Dexec.mainClass=org.cloudsimplus.examples.VmNetworkTrafficExample
```

This runs 13 fresh simulations: an idle baseline, then incoming-only,
outgoing-only, and bidirectional cases at 100, 500, 900, and 1,200 Mbps per active
direction. Default capacity is 1,000 Mbps per direction; generation lasts 10
simulated seconds, starting at simulation time 1 second after VM creation.

Run a single asymmetric case (100 Mbps incoming and 500 Mbps outgoing):

```bash
mvn -q -DskipTests compile org.codehaus.mojo:exec-maven-plugin:3.6.3:java \
  -Dexec.mainClass=org.cloudsimplus.examples.VmNetworkTrafficExample \
  -Dexec.args="--incoming 100 --outgoing 500 --duration 20 --capacity 1000 --interval 0.1 --latency 0.001 --output output/network-asymmetric"
```

An unspecified direction defaults to zero when either rate flag is supplied.
Thus `--incoming 500` tests incoming-only traffic, `--outgoing 500` tests
outgoing-only traffic, and `--incoming 0 --outgoing 0` runs a baseline.

To change all suite intensities, supply `--rates 50,200,750`. Do not combine
`--rates` with `--incoming` or `--outgoing`. Use `--help` for available options.
Options take separate values (`--duration 20`, not `--duration=20`).

## Intensity and delivery

Rates use decimal megabits per second (1 Mbps = 1,000,000 bits/s). The generator
offers one batch at the beginning of each interval:

```text
batch bytes = rate Mbps × 1,000,000 × interval seconds / 8
```

For example, 100 Mbps at a 0.1-second interval produces 1,250,000-byte batches.
These are aggregated data batches, not Ethernet packets. Reducing `--interval`
makes generation smoother but increases simulation events. Fractional final
intervals and cumulative byte rounding preserve the intended total data volume.

Each batch waits for its direction's link to become free, takes
`bytes × 8 / (capacity Mbps × 1,000,000)` seconds to serialize, then incurs the
configured one-way propagation delay. A batch counts as received only when its
entire delivery completes. Propagation does not occupy the serializer.

Keep capacity fixed while changing the offered traffic rate. Above capacity,
traffic queues; generation stops at the configured duration, and the simulation
continues until all queued traffic arrives. Queues are unlimited, so there is
no loss or retransmission. A real network with finite buffers would behave
differently under overload.

## Results

The output directory contains:

* `summary.csv`: two rows per case, including inactive directions. It records
  endpoint and host IDs, offered rate, capacity, generated/received byte totals,
  received bytes during the generation window, window throughput, maximum
  queue waiting time, byte-weighted mean delivery delay, and drain time.
* `batches.csv`: a delivery trace containing direction, endpoint IDs, byte size,
  generation time, arrival time, and queue waiting time. Times are relative to
  traffic start (simulation time 1 second), not wall-clock time. To plot traffic
  intensity, sum generated or received bytes in time bins and multiply by
  `8 / (1,000,000 × bin width seconds)`.

Existing CSV files in the selected directory are replaced on each run. Choose
different `--output` directories when retaining multiple experiments.

The default suite was verified with these results (per active direction):

| Offered Mbps | Delivered during 10 s, Mbps | Maximum queue wait, ms | Drain after generation, s |
|---:|---:|---:|---:|
| 100 | 100 | 0 | 0 |
| 500 | 500 | 0 | 0 |
| 900 | 900 | 0 | 0 |
| 1,200 | 996 | 1,980 | 2.001 |

The overloaded case delivers 996 rather than exactly 1,000 Mbps within the
window because only complete batches are counted and propagation takes time.
All generated bytes are eventually delivered, including during the drain.
Throughput in a short bin may exceed capacity when a whole batch is counted at
arrival; the serializer still enforces capacity continuously. Decrease the batch
interval for finer time resolution.

## Scope and migration experiments

The link model lives entirely in this example. It does **not** use the native
`NetworkCloudlet`/switch delivery pipeline or the VM bandwidth utilization model.
Host bandwidth is provisioned for VM placement; VM bandwidth reservations are
set to 1 Mbps solely for admission. The explicit FIFO link capacity is the
authority for data transfer timing, not that reservation.

No CPU overhead, TCP/UDP behavior, host networking stack, NIC offloading, or
migration traffic is modeled. No compute cloudlets are submitted: the custom
traffic entity drives data delivery between the allocated VM endpoints.

This example satisfies configurable simulated traffic generation. To investigate
migration impact, a further change must make migration and background traffic
compete for the same relevant directional capacity. Existing migration examples
are unchanged; adding this example alone does not make them traffic-aware.
Likewise, networking CPU overhead needs an explicit calibrated CPU model.

## Validation

```bash
mvn -q -Dtest=VmNetworkTrafficExampleTest test
```

The tests cover idle behavior, endpoint directions and placement, independent
rates, outgoing-only traffic, full-duplex capacity, overload and queue draining,
byte conservation, partial intervals, propagation delay, and invalid settings.

For controlled **model estimates** of migration time under steady/bursty traffic,
traffic direction, memory size, link speed, and dirtying rate, see
[migration condition experiments](migration-conditions.md). This separate analytical
suite does not couple this FIFO traffic generator to native VM migration.
