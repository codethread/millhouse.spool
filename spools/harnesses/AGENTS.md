# Harnesses

Follow the repository AGENTS.md and strict spool boundaries. Run package checks
from this directory with `make check`; the repository workspace is checked only
by the root quality gate.

New capabilities target Codex and Pi. Claude and Cursor are maintenance-only:
preserve their integrations without extending feature parity unless explicitly
authorized. Their identity and prompt-injection paths are unchanged.

Native plugins remain independently installable. Never install into user homes
or restart a Weaver as a side effect of source delivery.
