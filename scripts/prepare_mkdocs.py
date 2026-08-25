"""Create the staged documentation tree consumed by MkDocs."""

from pathlib import Path
import shutil


REPO_ROOT = Path(__file__).resolve().parent.parent
DOCS_DIR = REPO_ROOT / ".mkdocs"

shutil.rmtree(DOCS_DIR, ignore_errors=True)
DOCS_DIR.mkdir()
(DOCS_DIR / "index.md").symlink_to("../README.md")
(DOCS_DIR / "spools").symlink_to("../spools", target_is_directory=True)
