# Sub-coordinator alias rollout

The shared `sub-coordinator` seat is a bounded coordination role, not another writer. Its alias-provided system guidance is the complete operational runbook. It initially resolves through Codex to the unqualified native model ID `gpt-5.6-luna` at explicit `max` effort. Existing `coordinator`, worker, and reviewer aliases are not changed.

A process-local `seat/sub-coordinator-terra` flag selects the authorized fallback: Codex with the unqualified native model ID `gpt-5.6-terra` at explicit `high` effort and the same runbook. The additive `sub-coordinator-sol` seat resolves through Codex to the unqualified native model ID `gpt-5.6-sol` at explicit `high` effort. It shares the common coordination contract with sustained-role framing and does not change the Luna default, Terra switch, ordinary `coordinator`, or `sol` aliases.

Both alias prompt values are provider-neutral Strand coordination guidance. Use the [planner/coordinator briefs](coordinator-handoff.md) for prepared intake, acknowledged ownership and canonical-root headed launches. Append the separate [Codex](coordinator-launch-codex.md) or explicitly selected [Pi](coordinator-launch-pi.md) launch policy; harness timing is not embedded in the common system prompt. The supported launch examples apply these policies explicitly, rather than claiming old sessions inherit new defaults. They make the global Mill user-controlled, prohibit replacing a running Weaver without explicit user sign-off, require payload-safe dispatch, and preserve unrelated owner and run state. Deprecated `agent-harness.spool` workspaces, source, and APIs must never be used; maintained harness work belongs in `harnesses.spool`. Historical selection and trial evidence belongs in [coordinator-field-notes.md](coordinator-field-notes.md), not in the shared runbook.

## Additive registration in a running world

`ct.spools.harnesses/register-alias!` changes one runtime-local registry entry immediately. It does not refresh modules, alter flags, mutate existing runs, or restart the Weaver. The checked-in startup module registers the alias durably on a later ordinary activation. Until consumers update their Codethread pin, the candidate namespace can be loaded from a reviewed checkout and one narrow registration function called through the supported live Weaver nREPL:

| Alias                 | Function                                             |
| --------------------- | ---------------------------------------------------- |
| `sub-coordinator`     | `ct.spools.codethread.sub-coordinator/register!`     |
| `sub-coordinator-sol` | `ct.spools.codethread.sub-coordinator/register-sol!` |

Register `sub-coordinator-sol` only after its exact source commit passes required quality and review. The parent owns any staged runtime adoption; source workers must not perform registration, refresh a runtime, restart a process, change a flag, update a source pin, or start a pilot.

The original pilot used parent `x4y0z` / task `irfb7`; that historical approval is not standing permission for new adoption. A currently authorized runtime owner must select the handoff boundary. Source workers must not mutate shared running worlds. Existing alias replacement or Weaver pickup requires its own explicit scoped authorization; the create-only recipe below is not that path.

```nu
let coord_ws = "/absolute/path/to/canonical/.millstrand"
let candidate = "/absolute/path/to/reviewed/millhouse.spool/spools/config/src/ct/spools/codethread/sub_coordinator.clj"

let registry_before = (^strand --workspace $coord_ws agent list --full | from json)
if (($registry_before | where name == "sub-coordinator" | length) != 0) {
  error make {msg: "sub-coordinator is already registered; refusing to replace it"}
}

let active_ids = (
  ^strand --workspace $coord_ws agent runs --active
  | from json
  | get id
)
let runs_before = (
  $active_ids
  | each {|run_id| ^strand --workspace $coord_ws show $run_id | from json }
)

let source_literal = ($candidate | to json)
let registration_template = r#'
(do
  (load-file __SOURCE__)
  (let [runtime ((requiring-resolve 'millstrand.api.current.alpha/runtime))
        register! (requiring-resolve
                   'ct.spools.codethread.sub-coordinator/register!)]
    (register! runtime)))
'#
let registration = (
  $registration_template | str replace __SOURCE__ $source_literal
)

$registration | ^mill weaver repl --workspace $coord_ws --stdin

let registry_after = (^strand --workspace $coord_ws agent list --full | from json)
let runs_after = (
  $active_ids
  | each {|run_id| ^strand --workspace $coord_ws show $run_id | from json }
)

if $registry_before != ($registry_after | where name != "sub-coordinator") {
  error make {msg: "registration changed an existing alias"}
}

let frozen_keys = [
  "harness/after"
  "harness/alias"
  "harness/appended-system-prompts"
  "harness/context"
  "harness/cwd"
  "harness/effort"
  "harness/env"
  "harness/extra-argv"
  "harness/generated"
  "harness/harness"
  "harness/logical-id"
  "harness/mode"
  "harness/model"
  "harness/overrides"
  "harness/prompt"
  "harness/published"
  "harness/request-fingerprint"
  "harness/request-id"
  "harness/resumes"
  "harness/root-targets"
  "harness/run"
  "harness/session-id"
  "harness/target"
  "identity/id"
  "identity/prompt"
]

let frozen_before = (
  $runs_before
  | each {|run|
      {
        id: $run.id
        settings: (
          $run.attributes
          | transpose key value
          | where {|entry| $entry.key in $frozen_keys }
          | sort-by key
        )
      }
    }
)
let frozen_after = (
  $runs_after
  | each {|run|
      {
        id: $run.id
        settings: (
          $run.attributes
          | transpose key value
          | where {|entry| $entry.key in $frozen_keys }
          | sort-by key
        )
      }
    }
)

if $frozen_before != $frozen_after {
  error make {msg: "registration changed frozen settings of an existing run"}
}

^strand --workspace $coord_ws agent list --full
```

The top-level `strand show RUN_ID` calls are intentional. Batteries `show` returns the full raw strand with the `attributes` map consumed by the frozen settings proof. `strand agent show RUN_ID` returns a lifecycle summary and omits that map; substituting it would break the proof.

The pre-registration guard fails before `register!` when `sub-coordinator` already exists. Run it only at a safe handoff where the parent owns alias registration and has excluded concurrent registrants. The catalog API replaces by name and does not offer an atomic create-only operation, so this procedure must not invent one or claim safety while another owner can race the guard. Compare an existing descriptor to the reviewed candidate and escalate instead of replacing it. Lifecycle fields can change naturally while runs execute, so the proof compares their frozen launch settings rather than whole run records.

For reviewed `sub-coordinator-sol` adoption, use the same guarded proof with exactly these substitutions:

- guard and postcheck the alias name `sub-coordinator-sol`;
- resolve and call `ct.spools.codethread.sub-coordinator/register-sol!`;
- require every pre-existing alias, flag, module status, and captured run's frozen launch settings to remain unchanged; and
- verify the new alias resolves to Codex, `gpt-5.6-sol`, and `high`.

Do not call both registration functions, change `seat/sub-coordinator-terra`, or replace an existing descriptor as part of that additive registration.

## Activation handoff and fallback

After review and disposable-world proof, the runtime owner may assign one bounded coordination slice at an ownership boundary. Launch from the assigned repository's canonical root, not a feature worktree or disposable coordinator checkout. Record the canonical CWD/workspace separately from source worktree and cleanup custody; retain target, stable request ID, run ID, native session, real goal and discoverable terminal, initial registry, and frozen settings for existing runs. Require successor acknowledgement before planner release. Verify ownership, task-versus-feature dispatch, payload-safe prompts, bounded waits, progress checks, rework, required review, and accepted handoff.

Do not switch candidates for ordinary latency or infrastructure failure. The runtime owner may enable the fallback only after recording the evidence, settling the exact run, and accepting custody at an explicit handoff:

```nu
^strand --workspace $coord_ws agent config set seat/sub-coordinator-terra true
^strand --workspace $coord_ws agent list --full
```

Start a fresh `sub-coordinator` assignment or targeted run with a new stable request ID after the prior run settles. The Terra candidate receives the same provider-neutral alias runbook.

Roll out to another running world only at its own explicit runtime-owner handoff. Preserve existing owners, run pointers, settings, dirty files, workflow gates, and FIFO position. Durable availability still requires a reviewed Codethread pin and the repository's normal coordinated activation; live registration is additive staging, not a substitute for pin rollout.
