#!/usr/bin/env python3
"""Plot controlled migration-model results. Requires matplotlib (no pandas)."""
import argparse
import csv
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

METHODS = {'PRE_COPY': 'Pre-copy', 'POST_COPY': 'Post-copy', 'HYBRID': 'Hybrid-copy'}
COLORS = ['#0072B2', '#D55E00', '#009E73']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, default=Path('output/experiments/migration-conditions.csv'))
    parser.add_argument('--output', type=Path, default=Path('output/experiments/migration-condition-plots'))
    args = parser.parse_args()
    with args.input.open(newline='') as source:
        rows = list(csv.DictReader(source))
    args.output.mkdir(parents=True, exist_ok=True)
    index = ['# Migration condition plots', '',
             'Synthetic fluid-model estimates; migration time includes transfer after VM resume.',
             'Directions are relative to the source host. Destination background load is zero.',
             'Bursty traffic: 10 seconds on / 10 seconds off, peak = 2 × mean.',
             'Bands show min–max over four start phases, not statistical confidence intervals.', '']

    def save(fig, name, title, explanation):
        for ax in fig.axes:
            upper = max(other.dataLim.ymax for other in ax.get_shared_y_axes().get_siblings(ax))
            ax.set_ylim(0, max(upper * 1.08, 0.01))
        fig.suptitle(title, fontsize=15, fontweight='bold')
        fig.text(0.5, 0.015, explanation, ha='center', fontsize=9)
        fig.tight_layout(rect=(0, 0.08, 1, 0.92))
        for extension in ('png', 'svg'):
            fig.savefig(args.output / f'{name}.{extension}', dpi=170)
        plt.close(fig)
        index.extend([f'## {title}', '', f'![{title}]({name}.png)', '', explanation, ''])

    def lines(ax, subset, xkey, ykey, pattern=None):
        for method, color in zip(METHODS, COLORS):
            group = [r for r in subset if r['method'] == method and (pattern is None or r['pattern'] == pattern)]
            xs = sorted({float(r[xkey]) for r in group})
            samples = [[float(r[ykey]) for r in group if float(r[xkey]) == x] for x in xs]
            means = [sum(values) / len(values) for values in samples]
            ax.plot(xs, means, marker='o', color=color, label=METHODS[method])
            if any(len(values) > 1 for values in samples):
                ax.fill_between(xs, [min(v) for v in samples], [max(v) for v in samples], color=color, alpha=0.15)
        ax.grid(alpha=0.2)
        ax.set_ylim(bottom=0)
        ax.legend(fontsize=9)

    traffic = [r for r in rows if r['sweep'] == 'traffic']
    for pattern in ('STEADY', 'BURSTY'):
        fig, axes = plt.subplots(1, 3, figsize=(14, 5), sharey=True)
        for ax, direction in zip(axes, ('INCOMING', 'OUTGOING', 'MIXED')):
            lines(ax, [r for r in traffic if r['direction'] == direction], 'mean_traffic_mbps', 'total_migration_time_s', pattern)
            ax.set_title(f'{direction.title()} traffic at source')
            ax.set_xlabel('Mean traffic per active direction (Mbps)')
        axes[0].set_ylabel('Total migration time (s)')
        save(fig, f'migration_time_vs_{pattern.lower()}_traffic_by_direction',
             f'Migration time vs {pattern.lower()} background traffic by direction',
             'Model: 8,192 MB VM; 1,000 Mbps full-duplex link; dirty rate 20 MB/s. ' +
             ('Bands: min–max across start phases 0, 5, 10, 15 s.' if pattern == 'BURSTY' else 'Incoming traffic uses the opposite direction; outgoing and mixed contend.'))

    for sweep, xkey, xlabel, subtitle in (
        ('memory', 'memory_mb', 'VM memory state (MB)', 'Link 1,000 Mbps; dirty rate 20 MB/s'),
        ('link', 'link_mbps', 'Link capacity (Mbps)', 'VM 8,192 MB; dirty rate 20 MB/s'),
        ('dirty-rate', 'dirty_rate_mb_s', 'Memory dirtying rate (MB/s)', 'VM 8,192 MB; link 1,000 Mbps'),
    ):
        fig, axes = plt.subplots(1, 2, figsize=(11, 5))
        for ax, metric, ylabel in zip(axes, ('total_migration_time_s', 'downtime_s'), ('Total migration time (s)', 'VM downtime (s)')):
            lines(ax, [r for r in rows if r['sweep'] == sweep], xkey, metric)
            ax.set_xlabel(xlabel)
            ax.set_ylabel(ylabel)
            ax.set_title(ylabel[:-4])
        save(fig, f'migration_time_and_downtime_vs_{sweep.replace("-", "_")}',
             f'Migration time and downtime vs {xlabel.split(" (")[0].lower()}',
             f'Model: {subtitle}; steady source outgoing traffic 250 Mbps. Other parameters held fixed.')

    fig, axes = plt.subplots(1, 3, figsize=(14, 5), sharey=True)
    for ax, method in zip(axes, METHODS):
        for pattern, style in (('STEADY', '-'), ('BURSTY', '--')):
            group = [r for r in traffic if r['direction'] == 'OUTGOING' and r['method'] == method and r['pattern'] == pattern]
            xs = sorted({float(r['mean_traffic_mbps']) for r in group})
            samples = [[float(r['total_migration_time_s']) for r in group if float(r['mean_traffic_mbps']) == x] for x in xs]
            line, = ax.plot(xs, [sum(v)/len(v) for v in samples], style, marker='o', label=pattern.title())
            ax.fill_between(xs, [min(v) for v in samples], [max(v) for v in samples], alpha=0.15, color=line.get_color())
        ax.set_title(METHODS[method])
        ax.set_xlabel('Mean source outgoing traffic (Mbps)')
        ax.set_ylim(bottom=0)
        ax.grid(alpha=0.2)
        ax.legend()
    axes[0].set_ylabel('Total migration time (s)')
    save(fig, 'migration_time_steady_vs_bursty_equal_mean_traffic',
         'Migration time: steady vs bursty traffic at equal mean load',
         'Model: 8,192 MB VM; link 1,000 Mbps; dirty rate 20 MB/s. Bands: range over four burst start phases.')
    (args.output / 'README.md').write_text('\n'.join(index), encoding='utf-8')
    print(f'Generated 6 named plots (PNG + SVG) and plot guide in {args.output}')


if __name__ == '__main__':
    main()
