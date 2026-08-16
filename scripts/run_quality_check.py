#!/usr/bin/env python3
"""Run a quality check while keeping failed-command output bounded and recoverable."""

from pathlib import Path
import os
import re
import subprocess
import sys
import tempfile

HEAD_BYTES = 12 * 1024
TAIL_BYTES = 4 * 1024


def report_path(label: str) -> Path:
    """Return a stable temporary report path for a check label."""
    safe_label = re.sub(r"[^a-zA-Z0-9_.-]+", "-", label).strip("-") or "check"
    return Path(tempfile.gettempdir()) / f"millhouse-quality-{safe_label}.log"


def print_excerpt(path: Path, size: int) -> None:
    """Print bounded report bytes, preserving both initial and final diagnostics."""
    with path.open("rb") as report:
        if size <= HEAD_BYTES + TAIL_BYTES:
            sys.stderr.buffer.write(report.read())
            return
        head = report.read(HEAD_BYTES)
        report.seek(-TAIL_BYTES, os.SEEK_END)
        tail = report.read()

    sys.stderr.buffer.write(head)
    if head and not head.endswith(b"\n"):
        sys.stderr.buffer.write(b"\n")
    omitted = size - len(head) - len(tail)
    sys.stderr.write(f"\n... {omitted} output bytes omitted ...\n\n")
    sys.stderr.buffer.write(tail)


def main() -> int:
    """Run the supplied command and progressively disclose failure output."""
    if len(sys.argv) < 3:
        print("usage: run_quality_check.py LABEL COMMAND [ARG ...]", file=sys.stderr)
        return 2

    label, command = sys.argv[1], sys.argv[2:]
    if os.environ.get("QUALITY_VERBOSE") == "1":
        return subprocess.run(command).returncode

    destination = report_path(label)
    with tempfile.NamedTemporaryFile(prefix="millhouse-quality-", delete=False) as output:
        working_path = Path(output.name)
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)

    size = working_path.stat().st_size
    if result.returncode == 0:
        with working_path.open("rb") as output:
            sys.stdout.buffer.write(output.read())
        working_path.unlink()
        return 0

    working_path.replace(destination)
    print_excerpt(destination, size)
    if size > HEAD_BYTES + TAIL_BYTES:
        print(
            f"\n{label}: failed; full output saved to {destination} "
            "(or rerun with QUALITY_VERBOSE=1).",
            file=sys.stderr,
        )
    else:
        print(f"\n{label}: failed; output saved to {destination}.", file=sys.stderr)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
