"""Stage repository documentation without generated or installed dependencies."""

from pathlib import Path
import shutil
import subprocess


REPO_ROOT = Path(__file__).resolve().parent.parent
DOCS_DIR = REPO_ROOT / ".mkdocs"

shutil.rmtree(DOCS_DIR, ignore_errors=True)
DOCS_DIR.mkdir()
(DOCS_DIR / "index.md").symlink_to(REPO_ROOT / "README.md")
paths = subprocess.check_output(
    ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
    cwd=REPO_ROOT,
).decode().split("\0")
for path in sorted(set(paths)):
    if "/guidance/templates/" in path:
        continue
    source = Path(path)
    if not path.startswith(("docs/", "spools/")):
        continue
    if source.suffix not in {".md", ".png", ".jpg", ".svg", ".gif"}:
        continue
    target = DOCS_DIR / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.symlink_to(REPO_ROOT / path)
