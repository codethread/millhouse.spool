import { execFileSync } from "node:child_process";
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const hook = fileURLToPath(
  new URL("../.claude-plugin/hooks/identity.sh", import.meta.url),
);

describe("Claude SessionStart identity hook", () => {
  it("routes registration explicitly without appending workspace instructions", () => {
    const root = realpathSync(mkdtempSync(join(tmpdir(), "claude-identity-")));
    try {
      execFileSync("git", ["init", "--quiet", root]);
      const workspace = join(root, ".millstrand");
      mkdirSync(workspace);
      const strand = join(root, "strand");
      const args = join(root, "args");
      const instruction = "Your Millstrand identity is fixture-identity.";
      writeFileSync(
        strand,
        '#!/usr/bin/env bash\nprintf "%s\\n" "$@" > "$FIXTURE_ARGS"\nprintf "%s\\n" "$FIXTURE_RESPONSE"\n',
        { mode: 0o755 },
      );
      const output = JSON.parse(
        execFileSync("bash", [hook], {
          encoding: "utf8",
          input: JSON.stringify({
            hook_event_name: "SessionStart",
            session_id: "fixture-session",
            cwd: root,
          }),
          env: {
            PATH: process.env.PATH,
            HOME: root,
            MILLSTRAND_CLAUDE_STRAND_BIN: strand,
            FIXTURE_ARGS: args,
            FIXTURE_RESPONSE: JSON.stringify({
              operation: "agent native-startup",
              identity: "fixture-identity",
              instruction,
            }),
          },
        }),
      );
      expect(output).toEqual({
        hookSpecificOutput: {
          hookEventName: "SessionStart",
          additionalContext: instruction,
        },
      });
      expect(readFileSync(args, "utf8").trim().split("\n")).toEqual([
        "--workspace",
        workspace,
        "--cwd",
        root,
        "--timeout",
        "3s",
        "agent",
        "native-startup",
        "claude",
        "fixture-session",
        "--model",
        "unknown",
      ]);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });

  it("returns visible unbound context instead of unsupported blocking fields", () => {
    const output = JSON.parse(
      execFileSync("bash", [hook], {
        encoding: "utf8",
        input: JSON.stringify({
          hook_event_name: "SessionStart",
          session_id: "",
        }),
      }),
    ) as Record<string, unknown>;

    expect(output).not.toHaveProperty("continue");
    expect(output).not.toHaveProperty("stopReason");
    expect(output).toMatchObject({
      systemMessage: expect.stringContaining("session is unbound"),
      hookSpecificOutput: {
        hookEventName: "SessionStart",
        additionalContext: expect.stringContaining("session is unbound"),
      },
    });
  });
});
