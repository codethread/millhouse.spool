import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const hook = fileURLToPath(
  new URL("../.claude-plugin/hooks/identity.sh", import.meta.url),
);

describe("Claude SessionStart identity hook", () => {
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
