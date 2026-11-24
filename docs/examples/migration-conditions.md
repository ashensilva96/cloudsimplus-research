# Migration time under different conditions

## Experiment plan

Compare pre-copy, post-copy, and hybrid migration while varying background traffic direction, average intensity, and steady versus periodic burst traffic. Independently sweep VM memory, link capacity, and page-dirtying rate, holding the other inputs fixed, and report total migration time separately from downtime.

## Run

From the repository root, use JDK 25:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
mvn -q -DskipTests compile org.codehaus.mojo:exec-maven-plugin:3.6.3:java \
  -Dexec.mainClass=org.cloudsimplus.examples.MigrationConditionsExperiment
python3 script/plot_migration_conditions.py
```

The plotter requires Matplotlib (`python3 -m pip install matplotlib` in your Python environment).
The existing `TrafficAwareMigrationExperiment` entry point also generates the new
`migration-conditions.csv` alongside its original results. The original seven-scenario
CSV schema is unchanged, so the existing report generator continues to work.
The new Java entry point optionally accepts a CSV path. The plotter accepts
`--input CSV_PATH --output PLOT_DIRECTORY`. Rerunning replaces the corresponding outputs.

## Controlled conditions (228 runs)

| Sweep | Values | Fixed conditions |
|---|---|---|
| Traffic direction and intensity | Incoming, outgoing, mixed; 0, 100, 250, 400 Mbps per active direction | 8,192 MB memory; 1,000 Mbps link; 20 MB/s dirtying |
| Traffic pattern | Steady; periodic bursts (10 s on, 10 s off) | Same long-run mean; burst peak is twice the mean |
| Burst start phase | 0, 5, 10, 15 s within the 20 s cycle | All traffic conditions and methods |
| Memory | 1,024; 2,048; 4,096; 8,192; 16,384 MB | 1,000 Mbps link; 20 MB/s dirtying; steady outgoing 250 Mbps |
| Link capacity | 500; 750; 1,000; 2,000; 10,000 Mbps | 8,192 MB memory; 20 MB/s dirtying; steady outgoing 250 Mbps |
| Dirtying rate | 0, 5, 20, 50, 80, 120 MB/s | 8,192 MB memory; 1,000 Mbps link; steady outgoing 250 Mbps |

All rates and memory sizes use decimal units: 1 MB = 8 megabits.
Incoming/outgoing are relative to the **source host**, which migrates to an otherwise
unloaded destination host. Full duplex means incoming background traffic does not
consume migration's outgoing capacity. Mixed traffic applies the listed rate in
both directions; its migration result equals outgoing-only under these assumptions.
This is an expected control, not evidence that incoming traffic never matters in a
real network. Destination ingress traffic, shared half-duplex links, and CPU overhead
would require additional models.

## Timing model and limits

This suite extends the standalone analytical migration experiment. It does not wire
background packets into CloudSim's native migration scheduler or change the existing
`Scenario1IdleVmMigrationExperiment` or `VmNetworkTrafficExample`.
Results are synthetic fluid-model estimates, not measured hypervisor performance.
Residual outgoing bandwidth is link capacity minus instantaneous background load.
Transfers integrate that capacity exactly across burst boundaries. Bursts continue
throughout migration and start at the recorded phase; they are deterministic periodic
bursts, not random sporadic arrivals. Equal mean refers to a whole burst cycle, not
necessarily each migration's finite window. No random repetitions or confidence
intervals are claimed. Shaded plot bands show the range across the four phases.

Pre-copy transfers dirty pages again until at most 64 MB remains or eight rounds
complete, then pauses to copy the remainder. Hybrid allows up to two pre-copy rounds,
pauses for up to 64 MB, and transfers the rest after resuming. Post-copy pauses for
64 MB and transfers the rest after resuming. Dirty pages are capped at the VM memory
size per round. The model omits page-fault latency, protocol overhead, CPU contention,
and application slowdown. It does not model dirty-page retransfers after resume.
Peak traffic must be strictly below link capacity; saturated and overloaded queues
are outside this model, rather than being replaced with artificial minimum bandwidth.

Total migration time ends when **all memory transfer completes**, including transfer
after resume. Downtime covers only paused transfer. CSV fields include the complete
configuration, transfer volume, and round count so each point can be reproduced.

## Named plots

Files are written as PNG and SVG in `output/experiments/migration-condition-plots/`.
Each has a descriptive title, axes with units, method legend, and fixed-condition caption.
The generated `README.md` is an illustrated plot guide.

| Filename stem | Meaning |
|---|---|
| `migration_time_vs_steady_traffic_by_direction` | Total time vs steady traffic intensity, split by direction |
| `migration_time_vs_bursty_traffic_by_direction` | Total time vs mean burst traffic, with phase ranges |
| `migration_time_steady_vs_bursty_equal_mean_traffic` | Steady and bursty source egress compared at equal mean load |
| `migration_time_and_downtime_vs_memory` | Memory size effect on completion and pause time |
| `migration_time_and_downtime_vs_link` | Link capacity effect on completion and pause time |
| `migration_time_and_downtime_vs_dirty_rate` | Dirty-page generation effect on completion and pause time |

## Validation

```bash
mvn -q -Dtest=MigrationConditionsExperimentTest,VmNetworkTrafficExampleTest test
```

Tests cover known transfer times, byte conservation for zero dirtying, directional
isolation, burst boundary integration, start phase, round limits, and invalid inputs.
