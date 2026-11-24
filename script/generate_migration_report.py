#!/usr/bin/env python3
"""Create the Markdown and PDF report from TrafficAwareMigrationExperiment CSV output."""
import csv
import sys
from collections import defaultdict
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import KeepTogether, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle


ROOT = Path(__file__).resolve().parents[1]
CSV_PATH = ROOT / "output/experiments/migration-results.csv"
OUT_DIR = ROOT / "output/reports"
MD_PATH = OUT_DIR / "traffic-aware-migration-experiment-report.md"
PDF_PATH = OUT_DIR / "traffic-aware-migration-experiment-report.pdf"


def load_rows():
    with CSV_PATH.open(newline="") as stream:
        return list(csv.DictReader(stream))


def f(row, key):
    return float(row[key])


def table_rows(rows, columns):
    return [[heading for _, heading, _ in columns]] + [
        [formatter(row) for _, _, formatter in columns] for row in rows
    ]


def markdown_table(data):
    widths = len(data[0])
    lines = ["| " + " | ".join(data[0]) + " |", "| " + " | ".join(["---"] * widths) + " |"]
    lines.extend("| " + " | ".join(str(cell) for cell in row) + " |" for row in data[1:])
    return "\n".join(lines)


def select(rows, scenario):
    return [row for row in rows if row["scenario"] == scenario]


def summary_tables(rows):
    scenario1 = select(rows, "1 Single idle VM")
    s1 = [row for row in scenario1 if row["traffic"] in {"clear path", "source egress and destination ingress"}]
    s2 = select(rows, "2 Single VM workload")
    s2_summary = []
    for priority in ("high", "medium", "low"):
        group = [r for r in s2 if r["priority"] == priority]
        for requested in ("pre-copy", "post-copy", "hybrid-copy", "traffic-aware"):
            subset = [r for r in group if r["requested_method"] == requested]
            s2_summary.append({
                "priority": priority, "requested_method": requested,
                "selected_method": subset[0]["selected_method"],
                "total_migration_time_s": sum(f(r, "total_migration_time_s") for r in subset) / len(subset),
                "downtime_s": sum(f(r, "downtime_s") for r in subset) / len(subset),
                "post_resume_dependency_mb": sum(f(r, "post_resume_dependency_mb") for r in subset) / len(subset),
            })
    return s1, s2_summary


def build_markdown(rows):
    s1, s2 = summary_tables(rows)
    scenario3 = select(rows, "3 Serial versus parallel")
    scenario4 = select(rows, "4 High priority multiple VMs")
    scenario5 = select(rows, "5 Low priority multiple VMs")
    scenario6 = select(rows, "6 Priority trade off")
    scenario7 = select(rows, "7 CPU intensive QuickSort")
    common = [
        ("requested_method", "Requested method", lambda r: r["requested_method"]),
        ("selected_method", "Executed method", lambda r: r["selected_method"]),
        ("execution_mode", "Mode", lambda r: r["execution_mode"]),
        ("total_migration_time_s", "Migration time s", lambda r: f"{f(r, 'total_migration_time_s'):.2f}"),
        ("downtime_s", "Downtime s", lambda r: f"{f(r, 'downtime_s'):.3f}"),
        ("post_resume_dependency_mb", "Post resume MB", lambda r: f"{f(r, 'post_resume_dependency_mb'):.1f}"),
    ]
    s1_cols = [("traffic", "Traffic condition", lambda r: r["traffic"])] + common[:2] + common[3:5]
    s2_cols = [
        ("priority", "Priority", lambda r: r["priority"]),
        ("requested_method", "Requested method", lambda r: r["requested_method"]),
        ("selected_method", "Executed method", lambda r: r["selected_method"]),
        ("total_migration_time_s", "Mean time s", lambda r: f"{f(r, 'total_migration_time_s'):.2f}"),
        ("downtime_s", "Mean downtime s", lambda r: f"{f(r, 'downtime_s'):.3f}"),
        ("post_resume_dependency_mb", "Mean post resume MB", lambda r: f"{f(r, 'post_resume_dependency_mb'):.1f}"),
    ]
    multi_cols = [
        ("workload", "VM pair", lambda r: r["workload"]),
        ("priority", "Priority", lambda r: r["priority"]),
    ] + common
    cpu_cols = [
        ("requested_method", "Method", lambda r: r["requested_method"]),
        ("selected_method", "Executed method", lambda r: r["selected_method"]),
        ("total_migration_time_s", "Migration time s", lambda r: f"{f(r, 'total_migration_time_s'):.2f}"),
        ("downtime_s", "Downtime s", lambda r: f"{f(r, 'downtime_s'):.3f}"),
        ("cpu_utilization_pct", "CPU %", lambda r: f"{f(r, 'cpu_utilization_pct'):.0f}"),
        ("application_slowdown_pct", "Modelled slowdown %", lambda r: f"{f(r, 'application_slowdown_pct'):.0f}"),
    ]
    return f"""# Traffic Aware VM Migration Experiment Report

## Outcome

This report contains {len(rows)} deterministic model runs covering all seven requested migration scenarios. Under the stated 1 Gbps link and 500 Mbps competing egress/ingress traffic, an 8 GB idle VM takes 131.07 s with post-copy and 142.40 s with hybrid-copy or pre-copy. Pre-copy has no post-resume memory dependency for this idle workload; post-copy reduces direct downtime to 1.024 s but resumes with 8,128 MB still fetched on demand.

These are model results, not a replication of the unnamed original study. The source workload traces, exact traffic-aware algorithm, hypervisor implementation, and host topology were not provided.

## Model and reproducibility

- VM memory state: 8,192 MB; minimum execution state: 64 MB.
- Network link: 1,000 Mbps. Available migration bandwidth is `1,000 - max(source egress traffic, destination ingress traffic)` Mbps. Thus applicable 500 Mbps traffic leaves 500 Mbps for migration.
- Dirty-page generation is workload-specific (5 to 80 MB/s). Pre-copy stops at 64 MB remaining or after eight rounds. Hybrid runs two pre-copy rounds, transfers the 64 MB execution state while paused, then fetches remaining dirty state after resume.
- Traffic-aware policy: high selects post-copy, medium selects hybrid-copy, and low selects pre-copy. It does not claim a hidden throughput advantage; it selects an explicit trade-off under observed traffic.
- Parallel two-VM runs split the available migration bandwidth evenly. Reported multi-VM downtime is the worst individual VM downtime; serial migration completes VMs one after another.
- QuickSort CPU figures are a workload-impact model, not a measurement from a native QuickSort binary.

The complete machine-readable output is `output/experiments/migration-results.csv`, produced by `TrafficAwareMigrationExperiment`.

## 1 Single idle VM migration

{markdown_table(table_rows(s1, s1_cols))}

The two noninterfering traffic cases have the same 1,000 Mbps result, as do the two contending cases at 500 Mbps; the raw CSV retains all four requested traffic conditions.

## 2 Single VM application workloads

Table values are means across transactional, analytical, content-delivery, development/testing, batch, and archival/idle workloads, each with 500 Mbps contending background traffic.

{markdown_table(table_rows(s2, s2_cols))}

The selected traffic-aware method changes with priority: post-copy for quick resume, hybrid for balance, and pre-copy for no residual remote-memory dependency after resume.

## 3 Serial versus parallel migration

{markdown_table(table_rows(scenario3, multi_cols))}

Parallel execution shortens wall-clock completion when capacity sharing does not outweigh queueing, but increases each VM's migration-time exposure because each VM receives half of the available bandwidth.

## 4 High priority multiple VM migration

{markdown_table(table_rows(scenario4, multi_cols))}

## 5 Low priority multiple VM migration

{markdown_table(table_rows(scenario5, multi_cols))}

## 6 Medium priority multiple VM migration

{markdown_table(table_rows(scenario6, multi_cols))}

High priority favors shortest stop phase, medium uses a two-round hybrid balance, and low priority completes serially with pre-copy to avoid a post-resume memory dependency.

## 7 CPU intensive QuickSort during migration

{markdown_table(table_rows(scenario7, cpu_cols))}

## Interpretation and limitations

Migration time includes all modelled memory transfer through completion. Downtime means the paused execution interval. Post-resume dependency is memory still obtained after the VM resumes; it is an application-risk indicator, not additional downtime. Network traffic is represented as steady bandwidth consumption, and dirtying is a steady workload rate. Before using these results for deployment sizing, replace the assumed dirtying rates and CPU impact with monitored traces, record actual source/destination NIC counters, use the hypervisor's measured pause interval, and repeat each run enough times to report variance.
"""


def styled_table(data, widths=None):
    table = Table(data, colWidths=widths, repeatRows=1, hAlign="LEFT")
    commands = [
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#17365D")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("FONTSIZE", (0, 0), (-1, -1), 7.5),
        ("LEADING", (0, 0), (-1, -1), 9),
        ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#D9D9D9")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 5),
        ("RIGHTPADDING", (0, 0), (-1, -1), 5),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]
    for index in range(1, len(data)):
        if index % 2 == 0:
            commands.append(("BACKGROUND", (0, index), (-1, index), colors.HexColor("#F2F6FA")))
    table.setStyle(TableStyle(commands))
    return table


def build_pdf(rows):
    s1, s2 = summary_tables(rows)
    styles = getSampleStyleSheet()
    title = ParagraphStyle("TitleBlack", parent=styles["Title"], fontName="Helvetica-Bold", textColor=colors.black, spaceAfter=12)
    heading = ParagraphStyle("HeadingBlack", parent=styles["Heading2"], fontName="Helvetica-Bold", textColor=colors.black, spaceBefore=10, spaceAfter=5)
    body = ParagraphStyle("Body", parent=styles["BodyText"], fontName="Helvetica", fontSize=9.5, leading=13, spaceAfter=7)
    document = SimpleDocTemplate(str(PDF_PATH), pagesize=letter, rightMargin=0.48*inch, leftMargin=0.48*inch, topMargin=0.55*inch, bottomMargin=0.55*inch)
    story = [
        Paragraph("Traffic Aware VM Migration Experiment Report", title),
        Paragraph("Outcome", heading),
        Paragraph(f"This report contains {len(rows)} deterministic model runs covering the seven requested scenarios. Under a 1 Gbps link with 500 Mbps competing egress/ingress traffic, an 8 GB idle VM takes 131.07 s with post-copy and 142.40 s with hybrid-copy or pre-copy. These are reproducible model results, not a replication of an unspecified original study.", body),
        Paragraph("Model and reproducibility", heading),
        Paragraph("State: 8,192 MB; minimum execution state: 64 MB. Available migration bandwidth is 1,000 minus the larger of source egress and destination ingress traffic in Mbps. Pre-copy stops at 64 MB or eight rounds. Hybrid uses two pre-copy rounds then resumes after transferring the 64 MB execution state. The traffic-aware policy selects post-copy for high priority, hybrid-copy for medium, and pre-copy for low priority.", body),
        Paragraph("1 Single idle VM migration", heading),
    ]
    s1_cols = [("traffic", "Traffic condition", lambda r: r["traffic"]), ("requested_method", "Method", lambda r: r["requested_method"]), ("total_migration_time_s", "Time s", lambda r: f"{f(r, 'total_migration_time_s'):.2f}"), ("downtime_s", "Downtime s", lambda r: f"{f(r, 'downtime_s'):.3f}")]
    story += [styled_table(table_rows(s1, s1_cols), [2.2*inch, 1.15*inch, 0.8*inch, 0.8*inch]), Spacer(1, 8)]
    story += [Paragraph("2 Single VM application workloads", heading), Paragraph("The following values are means across six workload types with 500 Mbps contending background traffic.", body)]
    s2_cols = [("priority", "Priority", lambda r: r["priority"]), ("requested_method", "Requested", lambda r: r["requested_method"]), ("selected_method", "Executed", lambda r: r["selected_method"]), ("total_migration_time_s", "Mean time s", lambda r: f"{f(r, 'total_migration_time_s'):.2f}"), ("downtime_s", "Mean downtime s", lambda r: f"{f(r, 'downtime_s'):.3f}"), ("post_resume_dependency_mb", "Post resume MB", lambda r: f"{f(r, 'post_resume_dependency_mb'):.1f}")]
    story += [styled_table(table_rows(s2, s2_cols), [0.6*inch, 0.85*inch, 0.85*inch, 0.8*inch, 0.9*inch, 1.0*inch]), PageBreak()]
    multi_cols = [("workload", "VM pair", lambda r: r["workload"]), ("priority", "Priority", lambda r: r["priority"]), ("requested_method", "Requested", lambda r: r["requested_method"]), ("selected_method", "Executed", lambda r: r["selected_method"]), ("execution_mode", "Mode", lambda r: r["execution_mode"]), ("total_migration_time_s", "Time s", lambda r: f"{f(r, 'total_migration_time_s'):.2f}"), ("downtime_s", "Downtime s", lambda r: f"{f(r, 'downtime_s'):.3f}")]
    for scenario, label in [
        ("3 Serial versus parallel", "3 Serial versus parallel migration"),
        ("4 High priority multiple VMs", "4 High priority multiple VM migration"),
        ("5 Low priority multiple VMs", "5 Low priority multiple VM migration"),
        ("6 Priority trade off", "6 Medium priority multiple VM migration"),
    ]:
        story += [Paragraph(label, heading), styled_table(table_rows(select(rows, scenario), multi_cols), [1.1*inch, 0.55*inch, 0.75*inch, 0.75*inch, 0.55*inch, 0.6*inch, 0.7*inch]), Spacer(1, 8)]
    story += [Paragraph("7 CPU intensive QuickSort during migration", heading)]
    cpu_cols = [("requested_method", "Method", lambda r: r["requested_method"]), ("selected_method", "Executed", lambda r: r["selected_method"]), ("total_migration_time_s", "Time s", lambda r: f"{f(r, 'total_migration_time_s'):.2f}"), ("downtime_s", "Downtime s", lambda r: f"{f(r, 'downtime_s'):.3f}"), ("cpu_utilization_pct", "CPU %", lambda r: f"{f(r, 'cpu_utilization_pct'):.0f}"), ("application_slowdown_pct", "Slowdown %", lambda r: f"{f(r, 'application_slowdown_pct'):.0f}")]
    story += [styled_table(table_rows(select(rows, "7 CPU intensive QuickSort"), cpu_cols), [0.9*inch, 0.9*inch, 0.7*inch, 0.8*inch, 0.85*inch, 0.95*inch]), Spacer(1, 10)]
    story += [Paragraph("Interpretation and limitations", heading), Paragraph("Migration time includes all modelled memory transfer. Downtime is only the paused execution interval. Post-resume dependency is the memory fetched after VM resume and indicates application risk rather than extra downtime. The workload dirtying and CPU impact values are steady-state assumptions. Replace them with monitored traces and repeat experiments to report variance before using the results for production sizing.", body)]

    def footer(canvas, doc):
        canvas.saveState()
        canvas.setFont("Helvetica", 8)
        canvas.setFillColor(colors.HexColor("#555555"))
        canvas.drawRightString(letter[0] - 0.48*inch, 0.32*inch, f"Page {doc.page}")
        canvas.restoreState()

    document.build(story, onFirstPage=footer, onLaterPages=footer)


def main():
    rows = load_rows()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    MD_PATH.write_text(build_markdown(rows), encoding="utf-8")
    build_pdf(rows)
    print(MD_PATH)
    print(PDF_PATH)


if __name__ == "__main__":
    main()
