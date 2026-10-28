#!/usr/bin/env python3
"""Consolidates the per-job difficulty report folders (DifficultyReport output) into one PDF plus merged CSV and JSON.

    difficulty_pdf.py <folder containing one sub-folder per job> <out dir>

Missing or partial jobs are reported, never fatal: whatever data exists is merged and drawn.
"""
import csv, json, os, sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages

src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
tables = {"windows.csv": [], "castles.csv": [], "solver.csv": []}
runs, notes = [], []
for job in sorted(os.listdir(src)):
    d = os.path.join(src, job)
    if not os.path.isdir(d):
        continue
    for name, rows in tables.items():
        p = os.path.join(d, name)
        if os.path.exists(p):
            with open(p, newline="") as f:
                rows.extend(csv.DictReader(f))
    p = os.path.join(d, "report.json")
    if os.path.exists(p):
        try:
            runs.extend(json.load(open(p))["runs"])
        except Exception as e:  # a job killed while writing leaves a torn file
            notes.append(f"{job}: report.json unreadable ({e})")
    else:
        notes.append(f"{job}: no report.json (job failed or timed out before its first run finished)")

for name, rows in tables.items():
    if rows:
        with open(os.path.join(out, name), "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)
json.dump({"runs": runs, "notes": notes}, open(os.path.join(out, "difficulty_report.json"), "w"), indent=1)

MODES = ["NORMAL", "PUNISHER", "NGH"]
castles = tables["castles.csv"]


def series(mode, field):
    rows = sorted((r for r in castles if r["mode"] == mode), key=lambda r: (int(r["seed"]), int(r["castle"])))
    by = {}
    for r in rows:
        by.setdefault(int(r["castle"]), []).append(float(r[field]))
    xs = sorted(by)
    return xs, [sum(by[x]) / len(by[x]) for x in xs]


with PdfPages(os.path.join(out, "difficulty_report.pdf")) as pdf:
    fig = plt.figure(figsize=(8.27, 11.69))
    lines = ["Upwardly - difficulty report (diagnostic only; never used by generation)", ""]
    for r in runs:
        lines.append(f"{r['mode']:9s} seed {r['seed']}: reached {r['reached']:.0f} m, {r['slices']} slices, {r['seconds']:.0f} s, "
                     f"solver failures {r['solver_failures']}" + (f"  STOPPED: {r['generation_error']}" if r.get("generation_error") else ""))
    lines += [""] + notes
    fails = [s for s in tables["solver.csv"] if s["completed"] != "true"]
    lines += ["", f"Solver progression failures: {len(fails)} of {len(tables['solver.csv'])} slices"]
    for s in fails[:40]:
        lines.append(f"  {s['mode']} seed {s['seed']} slice {s['slice']} ({float(s['y0']):.0f}-{float(s['y1']):.0f} m) link {s['failed_link']}: {s['fail_info'][:70]}")
    fig.text(0.05, 0.97, "\n".join(lines), va="top", family="monospace", fontsize=6.5)
    pdf.savefig(fig); plt.close(fig)

    for field, title in [("mean_adj", "Mean adjusted difficulty per 25 m window"), ("low_pct", "Low-challenge windows (%)"),
                         ("hazard_families", "Hazard families per castle"), ("protective", "Club deductions per castle"),
                         ("interaction", "Interaction bonus per castle"), ("gen_seconds", "Generation time per castle (s)"),
                         ("solver_failures", "Solver failures per castle")]:
        fig, ax = plt.subplots(figsize=(8.27, 5))
        for m in MODES:
            xs, ys = series(m, field)
            if xs:
                ax.plot(xs, ys, marker="o", label=m)
        ax.set_title(title + " (mean over seeds)"); ax.set_xlabel("castle"); ax.set_xticks(range(1, 11)); ax.grid(alpha=0.3); ax.legend()
        pdf.savefig(fig); plt.close(fig)

    w = tables["windows.csv"]
    if w:
        fig, ax = plt.subplots(figsize=(11.69, 5))
        for m in MODES:
            pts = [(float(r["y0"]), float(r["adjusted_net"])) for r in w if r["mode"] == m and r["seed"] == w[0]["seed"]]
            if pts:
                ax.plot([p[0] for p in pts], [p[1] for p in pts], lw=0.7, label=m)
        ax.axhline(1.0, color="grey", ls="--", lw=0.6)
        ax.set_title(f"Adjusted difficulty per 25 m window, seed {w[0]['seed']} (dashed: low-challenge line)"); ax.set_xlabel("height (m)"); ax.legend()
        pdf.savefig(fig); plt.close(fig)
print(f"merged {len(runs)} runs, {len(castles)} castle rows, {len(tables['solver.csv'])} solver rows; notes: {notes}")
