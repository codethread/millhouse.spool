#!/usr/bin/env python3
"""Fail when a local inline Markdown link points to a missing file."""

from pathlib import Path
import re
import sys
from urllib.parse import unquote

LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")


def findings(root: Path) -> list[str]:
    """Return missing local-link findings below root."""
    missing = []
    for source in sorted(root.glob("**/*.md")):
        text = source.read_text()
        for match in LINK.finditer(text):
            destination = match.group(1).split("#", 1)[0]
            if not destination or "://" in destination or destination.startswith("mailto:"):
                continue
            target = source.parent / unquote(destination)
            if not target.exists():
                line = text.count("\n", 0, match.start()) + 1
                missing.append(f"{source}:{line}: missing local link {match.group(1)}")
    return missing


def main() -> int:
    """Check repository Markdown links."""
    missing = findings(Path("."))
    if missing:
        print("\n".join(missing), file=sys.stderr)
        return 1
    print("markdown-links: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
