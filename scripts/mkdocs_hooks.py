"""Rewrite repository-relative links that leave the staged docs collection.

The MkDocs collection is a symlinked projection of repository Markdown. Source
pages can link to tests and other repository files that belong in source
control but not in the generated site. This hook changes those links to GitHub
blob URLs. The strict build then reports only links that are genuinely broken.
"""

import os
import posixpath
import re


_LINK = re.compile(r"(\[[^\]]*\]\()([^)\s#]+)(#[^)\s]*)?(\))")


def on_page_markdown(markdown, page, config, files):
    """Convert existing out-of-collection repository links to source URLs."""
    repo_url = (config.get("repo_url") or "").rstrip("/")
    if not repo_url:
        raise ValueError("mkdocs_hooks requires repo_url to rewrite source links")
    repo_root = os.path.dirname(os.path.abspath(config["config_file_path"]))
    src_dir = posixpath.dirname(page.file.src_uri)

    def rewrite(match):
        prefix, target, anchor, suffix = (
            match.group(1),
            match.group(2),
            match.group(3) or "",
            match.group(4),
        )
        if "://" in target or target.startswith(("mailto:", "/")):
            return match.group(0)
        resolved = posixpath.normpath(posixpath.join(src_dir, target))
        if resolved.startswith(".."):
            return match.group(0)
        if files.get_file_from_path(resolved) is not None:
            return match.group(0)
        if os.path.exists(os.path.join(repo_root, resolved)):
            return f"{prefix}{repo_url}/blob/main/{resolved}{anchor}{suffix}"
        return match.group(0)

    return _LINK.sub(rewrite, markdown)
