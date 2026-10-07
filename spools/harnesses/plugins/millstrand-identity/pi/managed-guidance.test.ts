import { describe, expect, it } from "vitest";
import {
  type ManagedGuidanceBundle,
  renderManagedGuidance,
} from "./managed-guidance.js";

const bundle: ManagedGuidanceBundle = {
  schema: "millstrand.agent-guidance-bundle/v1",
  operation: "agent startup",
  "run-id": "fixture-run",
  attempt: 1,
  invocation: "fixture-invocation",
  harness: "pi",
  "native-session-id": "fixture-session",
  identity: "fixture-identity",
  "strand-id": "fixture-strand",
  workspace: "/fixture/.millstrand",
  transport: "native-v1",
  "bundle-sha256": "a".repeat(64),
  "capability-sha256": "b".repeat(64),
  context: {
    schema: "millstrand.agent-managed-context/v1",
    "identity-instruction": "Use fixture-identity.",
    "appended-system-prompts": ["First guidance.", "Second guidance."],
  },
};

describe("managed guidance rendering", () => {
  it("preserves ordered guidance and current-run context without workspace routing", () => {
    const rendered = renderManagedGuidance(bundle);
    expect(rendered).toContain(
      "Use fixture-identity.\n\nFirst guidance.\n\nSecond guidance.\n\nCurrent Millstrand run: fixture-run.",
    );
    expect(rendered).toContain(
      "This is the current managed guidance; earlier run guidance is historical.",
    );
    expect(rendered).not.toContain("--workspace");
    expect(rendered).not.toContain(bundle.workspace);
  });
});
