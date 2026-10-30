#!/usr/bin/env python3
"""Prints the certification package's KPI scorecard as Markdown (job log and step summary), so the result is readable without downloading
the artifact. Usage: cert_scorecard.py PACKAGE_DIR (the folder holding kpi_measurements.json)."""
import json, os, sys

def main():
    d = json.load(open(os.path.join(sys.argv[1], "kpi_measurements.json")))
    out = ["## Certification KPI scorecard", "", "| KPI | name | status | source | failing / unverified checks (measured) |", "|---|---|---|---|---|"]
    for k in d["kpis"]:
        bad = [c for c in k.get("checks", []) if c.get("status") not in ("PASS",)]
        det = "; ".join(f"{c['name']}: {c['status']} ({str(c.get('measured', ''))[:80]})" for c in bad[:4]) + (f"; +{len(bad) - 4} more" if len(bad) > 4 else "")
        out.append(f"| {k['id']} | {k['name']} | {k['status']} | {k.get('status_source', '')} | {det or '-'} |")
    print("\n".join(out))

if __name__ == "__main__":
    main()
