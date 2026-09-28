#!/usr/bin/env python3
"""Run three sequential fresh-JVM Millhouse fixture samples."""

import argparse
import datetime as dt
import json
import os
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
    parser.add_argument("--core-root", type=pathlib.Path, required=True)
    parser.add_argument("--counted", action="store_true")
    parser.add_argument("--samples", type=int, default=3)
    args = parser.parse_args()
    if args.samples < 1:
        parser.error("--samples must be positive")

    report_dir = pathlib.Path(__file__).resolve().parent
    repo_root = report_dir.parent.parent.parent
    raw_dir = report_dir / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    results_path = raw_dir / f"{args.label}.json"
    default_override = report_dir / "world_fixture_override.clj"
    counted_override = report_dir / "counted_world_fixture.clj"
    counted_bare = report_dir / "counted_bare_fixture.clj"
    if args.fixture == "world":
        preload = counted_override if args.counted else default_override
    elif args.counted:
        preload = counted_bare
    else:
        preload = None

    command = ["clojure"]
    aliases = ""
    if preload:
        aliases = (
            f' :aliases {{:test {{:main-opts ["-i" "{preload}" '
            '"-m" "millhouse.test-runner"]}}}'
        )
    core_root = args.core_root.resolve()
    sdeps = (
        '{:deps {io.millstrand/millstrand {:local/root "'
        + str(core_root)
        + '"}}'
        + aliases
        + '}'
    )
    command += ["-Sdeps", sdeps, "-M:test", *NAMESPACES]

    results = []
    for sample in range(1, args.samples + 1):
        log_path = raw_dir / f"{args.label}-{sample}.log"
        started = utc_now()
        count_path = raw_dir / f"{args.label}-{sample}-counts.json"
        if args.counted and count_path.exists():
            count_path.unlink()
        start_ns = time.monotonic_ns()
        with log_path.open("w") as log:
            log.write(f"command: {shlex.join(command)}\n")
            log.write(f"sample: {sample}\nstarted: {started}\n\n")
            log.flush()
            environment = os.environ.copy()
            if args.counted:
                environment["BARE_RUNTIME_COUNT_OUTPUT"] = str(count_path)
            completed = subprocess.run(
                command,
                cwd=repo_root,
                env=environment,
                stdout=log,
                stderr=subprocess.STDOUT,
                check=False,
            )
        elapsed_ms = round((time.monotonic_ns() - start_ns) / 1_000_000, 3)
        finished = utc_now()
        counts = None
        if args.counted:
            if not count_path.exists():
                raise RuntimeError(f"counted fixture did not write {count_path}")
            counts = json.loads(count_path.read_text())
        with log_path.open("a") as log:
            log.write(f"\nexit: {completed.returncode}\n")
            log.write(f"finished: {finished}\n")
            log.write(f"elapsed_ms: {elapsed_ms}\n")
            if counts is not None:
                log.write(f"fixture_counts: {json.dumps(counts, sort_keys=True)}\n")
        result = {
            "label": args.label,
            "fixture": args.fixture,
            "sample": sample,
            "command": command,
            "core_root": str(core_root),
            "counted": args.counted,
            "fixture_counts": counts,
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
