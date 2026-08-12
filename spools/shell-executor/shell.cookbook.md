# Shell executor cookbook

The cookbook is for compositions involving the shell executor. The
[contract](./README.md) defines activation and authoring rules; the [API
reference](./shell.api.md) owns precise request, outcome, and coordinator
calls.

## Verify an agent's work before shipping

**Situation.** An agent changes a workspace and a deterministic machine check
must pass before the workflow can ship the result.

**Composition.** Keep the delegated work and the verification as separate
gates. The `:shell` gate depends on the `:subagent` gate, so the shell executor
does not inspect the workspace until the agent executor has closed its gate.

```clojure
(require '[millhouse.spools.workflow :as workflow])

(def implement-and-verify
  (workflow/workflow
    "Implement and verify"
    (workflow/gate :implement "Agent implements the feature" :subagent
                   :attributes {"agent-run/harness" "build"
                                "agent-run/prompt" "Implement per specs/feature.md"
                                "agent-run/cwd" "/path/to/worktree"})
    (workflow/gate :verify "Tests pass" :shell
                   :depends-on [:implement]
                   :attributes {"shell/argv" ["clojure" "-M:test"]
                                "shell/cwd" "/path/to/worktree"
                                "shell/timeout-secs" 600})
    (workflow/step :done "Mark complete" :self
                   :depends-on [:verify])))
```

**Why this shape.** Each executor owns one waiter and neither needs to know
about the other. The workflow graph supplies the composition boundary: agent
delivery is independently inspectable, and the objective check is independently
recoverable.

## Fan out independent release checks

**Situation.** One build produces several artifacts, and each artifact needs a
different operating-system check before a final publish step.

**Composition.** Close the build once, fan out to independent `:shell` gates,
then join them with a step that depends on all checks.

```clojure
(require '[millhouse.spools.workflow :as workflow])

(def release
  (workflow/workflow
    "Release"
    (workflow/step :build "Build artifacts" :self)
    (workflow/gate :jar "Verify the jar" :shell
                   :depends-on [:build]
                   :attributes {"shell/argv" ["test" "-s" "target/app.jar"]})
    (workflow/gate :docs "Verify generated docs" :shell
                   :depends-on [:build]
                   :attributes {"shell/argv" ["sh" "-c"
                                               "test -s docs/index.html"]})
    (workflow/step :publish "Publish release" :self
                   :depends-on [:jar :docs])))
```

**Why this shape.** The checks are independent graph work and can run on the
shell executor's worker pool concurrently. The publish step becomes ready only
when both checks close; a failed check remains a visible gate stall instead of
being mistaken for a successful build.

See the [workflow cookbook](../workflow/workflow.cookbook.md) for general gate
composition and the [API reference](./shell.api.md) for precise failure
inspection and recovery.
