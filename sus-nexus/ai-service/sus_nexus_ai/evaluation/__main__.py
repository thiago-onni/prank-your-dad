"""CLI: ``python -m sus_nexus_ai.evaluation run <agent> [--threshold 0.9] [--json]``."""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path

from sus_nexus_ai.evaluation.runner import COMPARATORS, run_eval


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m sus_nexus_ai.evaluation")
    sub = parser.add_subparsers(dest="command", required=True)
    run_p = sub.add_parser("run", help="executa a avaliação de um agente (ou 'all')")
    run_p.add_argument("agent", help="id do agente ou 'all'")
    run_p.add_argument("--threshold", type=float, default=None)
    run_p.add_argument("--evals-dir", type=Path, default=None)
    run_p.add_argument("--json", action="store_true", help="imprime relatório JSON")
    args = parser.parse_args(argv)

    agents = sorted(COMPARATORS) if args.agent == "all" else [args.agent]
    exit_code = 0
    for agent_id in agents:
        report = asyncio.run(run_eval(agent_id, evals_dir=args.evals_dir, threshold=args.threshold))
        if args.json:
            print(json.dumps(report.to_dict(), ensure_ascii=False, indent=2))
        else:
            flag = "OK " if report.meets_threshold else "FAIL"
            print(
                f"[{flag}] {agent_id}: {report.passed}/{report.total} "
                f"({report.accuracy:.1%}) — limiar {report.threshold:.0%}"
            )
            for r in report.results:
                if not r.passed:
                    print(f"   - caso {r.case_id}: {', '.join(r.mismatches)}")
        if not report.meets_threshold:
            exit_code = 1
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
