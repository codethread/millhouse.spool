#!/usr/bin/env python3
"""Run three sequential fresh-JVM Millhouse fixture samples."""

import argparse
import datetime as dt
import json
import pathlib
import shlex
import subprocess
import time


NAMESPACES = [
    "millhouse.workflow-runtime-test",
    "millhouse.test-support-test",
]


def utc_now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True)
    parser.add_argument("--fixture", choices=["world", "bare"], required=True)
    parser.add_argument("--core-root", type=pathlib.Path)
    args = parser.parse_args()

    report_dir = pathlib.Path(__file__).resolve().parent
    repo_root = report_dir.parent.parent.parent
    raw_dir = report_dir / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    results_path = raw_dir / f"{args.label}.json"
    override = report_dir / "world_fixture_override.clj"

    command = ["clojure"]
    aliases = ""
    if args.fixture == "world":
        aliases = (
            f' :aliases {{:test {{:main-opts ["-i" "{override}" '
            '"-m" "millhouse.test-runner"]}}}}'
        )
    if args.core_root:
        core_root = args.core_root.resolve()
        sdeps = (
            '{:deps {io.millstrand/millstrand {:local/root "'
            + str(core_root)
            + '"}}'
            + aliases
            + '}'
        )
        command += ["-Sdeps", sdeps]
    command += ["-M:test", *NAMESPACES]

    results = []
    for sample in range(1, 4):
        log_path = raw_dir / f"{args.label}-{sample}.log"
        started = utc_now()
        start_ns = time.monotonic_ns()
        with log_path.open("w") as log:
            log.write(f"command: {shlex.join(command)}\n")
            log.write(f"sample: {sample}\nstarted: {started}\n\n")
            log.flush()
            completed = subprocess.run(
                command,
                cwd=repo_root,
                stdout=log,
                stderr=subprocess.STDOUT,
                check=False,
            )
        elapsed_ms = round((time.monotonic_ns() - start_ns) / 1_000_000, 3)
        finished = utc_now()
        with log_path.open("a") as log:
            log.write(f"\nexit: {completed.returncode}\n")
            log.write(f"finished: {finished}\n")
            log.write(f"elapsed_ms: {elapsed_ms}\n")
        result = {
            "label": args.label,
            "fixture": args.fixture,
            "sample": sample,
            "command": command,
            "core_root": str(args.core_root.resolve()) if args.core_root else None,
            "started": started,
            "finished": finished,
            "elapsed_ms": elapsed_ms,
            "exit_code": completed.returncode,
            "log": str(log_path.relative_to(report_dir)),
        }
        results.append(result)
        results_path.write_text(json.dumps(results, indent=2) + "\n")
        if completed.returncode:
            raise SystemExit(completed.returncode)


if __name__ == "__main__":
    main()
