# Millstrand coordinator field notes

> Historical rollout record: paths, pins, and board states below describe the
> pre-consolidation ecosystem, not the current source workflow. For new work use
> [consolidation and handoff](../consolidation.md) and the owning workspace
> policy. No live activation is authorized by this record.

Working observations from the sibling recovery and open-card execution on 2026-09-13. The sub-coordinator alias from feature `jxvxu` has landed. These notes record its live trials and the operational evidence needed to refine its runbook. Keep adding concrete outcomes and counterexamples while work runs.

Start with the concise [sub-coordinator runbook](millstrand-sub-coordinator-runbook.md) for the operating procedure; use this file for its observed evidence and history.

Tracking: Codethread epic `w3oqr`, coordinator feature `x4y0z`, notes task `kejtr`. The user requested delegation through `strand agent`; delegated execution uses Pi. Built-in Codex/ChatGPT delegates were stopped.

## Protocol refinement evidence (14 September, after 08:38 UTC)

Feature `wzepr` promotes the agreed protocol, not a new model trial. Current procedure is the linked runbook and working handoff briefs. Historical Pi aliases, 45/55-second examples and disposable coordination checkouts below are not current defaults. Kanban-only completion, canonical coordinator roots and harness-specific 180-second event-aware waits supersede those recommendations, including the original audit's separate-ledger proposal.

The audit `ylkr8` at `/Users/ct/dev/evidence/luna-coordinator-audit-20260914/report.md` found that Pi `5pufa` completed source Land but missed required superseded PR closure and lost its feature-worktree CWD during cleanup. Its Codex observation was provisional at 06:17 UTC. Later `hohca` task notes establish same-writer repair `4103r`, accepted candidate `fcfc383848541c517e1ed455a8226d89a3a24c86`, Land `land-hohca-fcfc383-20260914`, PR #21 / merge `7e8ef94cec100db5cbf4d1d9977d73692ffbba45`, required basic review `mjo1z` and source cleanup. Land prematurely cascaded activation children as unactioned; the coordinator reopened them and recorded runtime-owner handoff `rprnm/0rwrh` before final closure. That is recovered completion, not flawless bookkeeping or proof the live aliases had loaded. Evaluation task `e7otl` remains pending.

Harnesses planner `qhpg2` prepared existing work, then successor `steady-lucid-swan` acknowledged task `60gpx` in note `1r5cz`: tracked `psu8g`, managed session `9eef536e-9083-4c7d-87ea-310b8c0ee071`, actual Codex thread/goal `01a09ec3-55d2-7f40-a1ef-8673dee79c6b`, terminal `agent-harnesses-codex-luna`, canonical Harnesses CWD. Only afterward did the planner close predecessor `q6ai2`, preserving settled `uea97` and source lineages. Evidence is in `/Users/ct/dev/evidence/harnesses-coordinator-recovery-20260914-citiq7k5/`. The 08:38 parent handoff records Devflow PR #27 / `3d880109` and Codethread PR #22 / `42c1ce14` accepted through ordinary Land/cleanup. Retained Harnesses `qfw9o/kyp3h` was only partial settled history; Agents `n9m9g` was source-only `a9fa4840`. Neither was represented as reviewed, landed or activated native guidance.

Those headed recovery launches used detailed **one-off direct Codex prompts**, not the newly shipped shared alias plus launch files. They support the protocol choices but do not prove effective shared defaults or a new cohort. Canonical Codethread still exposed old Pi coordinator aliases at source refinement intake; consumer pickup remains parent-owned. The observed `mill prime millstrand` binary 0.5.2/source 0.5.3 mismatch does not authorize stopping Mill or global installation changes. Source verification and actual runtime activation remain separate evidence.

## Interactive Goal trials (05:28 UTC, 14 September)

These are dated observations, not replacements for the current procedure in the runbook.

The headed Sol readiness pilot used the spool-shipped interactive wrapper, which created tracked run `i1ecw`, native session `6933e8b8-4486-4b43-a68d-ef5db162ddad`, and named terminal `agent-codethread-subcoord`. It proved the TUI was visible and accepted input only after the user explicitly confirmed it. It had no source assignment and did not prove goal persistence or a complete coordination cycle.

The first Terra trial initially had Strand metadata that was mistaken for a goal. The correction used the supported `/goal` command in the live TUI and verified actual Pi goal `7c89a630-e411-4c4c-bc49-5fd73c4a8b38` in both TUI and native session goal-state. Its automatic guard remained at the default `0/25`. Because activation was added to a busy TUI, kickoff consumption was a separate observation. The user's preferred launch contract is therefore an initial prompt that starts exactly `/goal <rest of prompt>`, followed by verification of real Pi goal state rather than a fabricated Strand goal row.

Terra run `ls18x` resolved through Pi to Terra/high, native session `beb4c359-95db-4da8-8853-0d2a21a639fd`, terminal `agent-millhouse-subcoord-terra`, and Millhouse coordinator task `3eogv`. It owned cards `7vglh` and `bnr5p`, resumed the retained sole Land writer, and started one independent pin writer. This proves launch and first dispatch, not yet sustained review, Land, cleanup, or goal completion.

The parallel Luna trial started with `/goal` in its initial prompt. Tracked run `5pufa` resolved through Pi to Luna/max, native session `023d9eff-2c78-4799-9752-cc039840e717`, named terminal `agent-codethread-subcoord-luna`, and real Pi goal `b6eda325-0b04-41ba-9af4-bf57158d3472`, active with the unchanged default `0/25` guard. Feature `kchhr` has one local owner and task `wcv6g`; sole Sol writer `7yil6` owns task `hdshy` and only the dedicated feature worktree at starting candidate `cb866ef`. The canonical coordination records remain separate from that execution worktree. Root retains historical PR16 and mentors without editing this source.

These trials also sharpened two live-CLI corrections. Current discovery is `strand help agent`, not stale `strand prime agent`. An uncertain publication is looked up by its stable idempotency key with `strand agent show --request <request-id>` before retrying. A ready run still needs an active, dependency-eligible target and real attempt/custody evidence.

A later root prompt incorrectly suggested `goal_wait` while Terra was waiting on workers. The correction belongs to the prompt contract, not to model evaluation: worker, review, and workflow observation uses `strand await`. `goal_wait` is only for an already-active Pi goal intentionally parked for an arranged external wake or deadline; naming a query in a note does not subscribe the goal to that query.

Live help reports a 1,800-second default for `strand await` and recommends reissuing waits at about 50 minutes to preserve the provider prompt cache. The 45-second inner and 55-second client pattern remains useful for short loops. `reason=timeout` is not a failure. Positive `agent-run-terminal` evidence means stopped or failed, not successful; positive `agent-run-settled` additionally requires provider process settlement before native resume. A failed row alone is not settlement. `agent-run-active --max-count 0` observes departure, not success.

Assignment completion has its own evidence. `agent-work-complete --param target=FEATURE --min-count 1` observes accepted done; `agent-work-complete-or-intervention` also detects abandonment or a failed serving head. Missing IDs do not satisfy positive waits, and an empty active-run list is not completion. With `stop-on-complete`, await the run, inspect its semantic result, and have the authorized coordinator accept and finish the target. After every bounded await, read coordinator and child mailboxes and check meaningful source, review, quality, or workflow progress before acting or waiting again.

## Handoff snapshot (04:25 UTC, 14 September)

Read the latest note on `x4y0z` and the local coordinator tasks before acting. Their current run and workflow state takes precedence over this dated snapshot.

The Mill remains PID `64448`, with its original 13 September start time. All seven alias consumer updates and all native identity startup activations are accepted. The original Pi startup incident from canonical Agents is fixed. The later optional Pi agent model-catalogue error is a separate open issue; the adapter writer's retained driver is a workaround, not its repair.

Harnesses orphan reconciliation `0xq77` is done. PR15 landed as `6410da676b3b41be55a58f4abc5fe773cfc75cb9`, with direct Oracle acceptance, normal basic review and 107 tests / 813 assertions. Root activated it through only the Harnesses Weaver: `84892` became `88907`, generation `c5690312-98c1-45bc-9564-a2e1cb14e2e6`. All captured worker rows, process trees, other Weaver generations and the original Mill were preserved. The accepted source hashes match the installed resources and the durable sweep is armed at 3,600,000 ms. Evidence is in `/Users/ct/dev/evidence/harnesses-orphan-activation-20260914T022400Z`.

Historical `as5dr`, `yzj7w` and `1urtj` now show Stopped / abandoned following supported explicit administrative reconciliation. They retain identity/session history and audit evidence, claim no provider exit, and refuse native reuse. Repeated reconciliation leaves rows and audit notes unchanged. The actual UI label function returns Stopped. Root task `m8ru6` remains active for Millhouse `p9a8g`, which needs the accepted Harnesses dependency installed first.

The old Harnesses coordinator `vm5zk` and the current worker runs naturally settled at a provider quota interruption around 02:48 UTC. The legacy run's real callback produced settled process-exit, the authentic provider failure, and a verified reusable session under the upgraded backend. This satisfies the last B9 operational boundary; `5v0ok` and parent `k3mob` are now complete. It does not claim that its unfinished source work succeeded. The quota boundary and preserved worktrees are recorded in `/Users/ct/dev/evidence/recovery-quota-boundary-20260914T025000Z`.

Refined Sol alias feature `mbygj` is done: Codethread PR19 landed as `0edfb4918ec7273115938f24631f5d7d44b2d44b`, after the request-key correction and one accepted direct basic review. Its source worktree was removed. Root added only `sub-coordinator-sol` to the running Harnesses catalogue from that exact source; existing aliases, frozen rows, modules and Weaver generations matched. Evidence: `/Users/ct/dev/evidence/sub-coordinator-sol-harnesses-20260914T024000Z`. The first successor `5m0rp` immediately hit quota. A later same-account quota check showed availability; one bounded native continuation `vranz` is running on fresh coordinator task `q6ai2`. It is preparing the handoff, pending root release. Predecessor `vm5zk` has already settled; do not resume both owners.

Harnesses owns two interrupted source lanes, whose partial work is preserved: Agents task `n9m9g` / `rtu0v`, source `/Users/ct/dev/projects/agents__feat--native-guidance-adapters-20260914`, runtime driver `/tmp/agents-native-guidance-driver.rvOS63`; and Harnesses task `qfw9o` / `kyp3h`, source `/Users/ct/dev/projects/harnesses.spool__feat--native-guidance-transport-20260914`. Both sessions are reusable and require continuation, checks, source acceptance and normal landing. Production native-guidance admission stays disabled. The accepted design is direct Oracle `lfk7y` on `b5bph`.

Shared pin task `0ucr4` under `ta2ip` has only its three-line uncommitted diff. Root replaced the mistakenly chosen Luna writer `91l4d` with Sol `svr11`, which found a real dependency conflict: Devflow's adapter local/root dependency cannot be composed with the intended paired Git coordinates. Note `sbunj` and predecessor note `kvini` describe it. The successor must obtain bounded Oracle packaging direction, delegate a Devflow source fix in its own feature worktree, then complete the coherent accepted pin and consumer updates. Do not silently drop a dependency or leave this as an unexplained external blocker.

Millhouse coordinator `0pln0` also settled at quota and has resumed as `1bt1p` on `qxlcv`. Its sole source lineage `xsz0n` retains a small test-teardown correction at pushed candidate `98542852` for feature `7vglh`. Earlier direct Oracle accepted the production repair; the basic reviewer found that a final test process could outlive its fixture. Finish that bounded teardown correction and checks, then use one focused direct Oracle follow-up as truthful evidence for the basic gate. Do not repeat overlapping broad reviews. Normal Land quality, FIFO and cleanup remain required; consumers must wait for the final accepted Land/Workflow pair.

Skein's completed coordinator `x6qtq` handed its remaining dependencies to root: `xahg3` awaits Devflow consumer adoption through `666xq`; `5enye` awaits Millhouse `7vglh`. Canonical Skein is accepted at `3c5062fc`, with all eligible local repair PRs landed and its five-reviewer roster installed. A new actionable dependency needs a fresh task/run in the retained coordinator checkout, not a continuation on its closed task. P3 `seg5v` / `8evdt` remains deferred product refinement. Devflow source PR26 / `47cf5f3` and its local installed guidance are accepted; remaining consumers still depend on the packaging and pin work above.

Root owns live Weaver activation, historical reconciliation, cross-repository handoffs, and these notes. Local coordinators own source, focused acceptance and normal landing. Deprecated `agent-harness.spool` stays entirely excluded.

## Recent corrections to the coordination loop

Skein's Luna repeatedly waited for published jobs whose frozen targets were already closed. They had never acquired process custody. The parent stopped those exact requests, handed local coordination to a fresh Terra/high run, and used new open tasks. Terra promptly launched real source workers and completed the missing final hello-world review. This is concrete evidence for changing that seat; it does not establish a universal model ranking.

A gate's static instruction may mention failed checks while its actual error is null and a healthy process owns the work. Read the error and custody before reporting failure. Likewise, a failed finish request can mean another owner already closed the card: read current state before retrying. Both occurred in this recovery, and neither warranted restarting a service.

Process provenance must come from the owning task, not only a JVM's source checkout. Disposable PID `71714` used Skein code but belonged to the completed Agents alias proof. Its task notes, exact workspace/generation, settled owner, and empty active-run/queue readback established safe cleanup. The supported workspace stop removed only that disposable Weaver; its evidence was retained. Unknown temporary processes remain outside that cleanup authority.

## Give the next coordinator a complete starting point

- Put the coordinator feature under its epic. Give it an owner, branch, recorded worktree, current run pointer, and child tasks.
- Keep a short ownership map on the feature and detailed evidence on the task being driven. Its latest note must say what to do next, not just what happened.
- Record the canonical coordination workspace separately from the execution repository/worktree. A card in Harnesses can own work in Millhouse or Agents. An empty local board therefore does not establish that the repository is idle.
- Preserve prior owners/run IDs in notes when changing the primary dashboard pointer. A custom recovery pointer alone leaves ordinary views showing an old coordinator.
- State the outcome and non-overlapping scope of each coordinator. In this run, Harnesses `129ar` owns the identity epic `k5zd2`; Millhouse's generic board coordinator must not also operate its identity worktree.

## Discover, dispatch, and keep one writer

Start with live help and prime: `strand --help`, `mill prime millstrand`, `strand prime kanban`, `strand prime agent`, and the relevant workflow help. Use an explicit `--workspace` for cross-repository coordination.

Inspect the board, dependencies, card notes, recorded worktree, and active runs before dispatch. Check actual worktree ownership; registry entries can outlive their processes. A worker exiting does not establish that its feature is done.

Verify the resolved harness/model with `strand agent list`. Use `strand agent` for delegation and Pi as the harness. Preserve explicitly required reviewer roles, including Oracle; a recovery Sol review cannot waive an Oracle gate.

`agent assign` generates a feature-claim instruction. Assigning coordinator task `538nl` generated a failed `kanban claim` against a task. For future launches, assign a feature or use an explicit `agent run --target TASK --prompt ...` under an already claimed feature; do not tell a worker to claim a task as a card.

Prepare the isolated worktree before launch. Use a stable request ID for each logical dispatch, retain the returned run ID, and distinguish coordinator work from the implementation worker's scope. Record the first substantive dispatch or action, then continue through the dependency graph.

A launch can succeed after its client reports a deadline. Millstrand's coordinator read back run `jx15x` and confirmed it was running after that exact case. Inspect the request/run before retrying; reuse the logical request ID instead of starting a second writer because the first response was uncertain.

Use payload files or direct structured argv for rich card bodies and prompts. Archive coordinator `v0zpb` observed shell command substitution corrupt a body containing backticks and shell parameter syntax, then repaired the owning card using a payload file. JSON encoding is not shell escaping. Verify the stored body when a command result suggests quoting or interpolation went wrong.

Be explicit about delegation depth. Millhouse repo coordinator `d23f7` asked `4a8yy` to delegate implementation, so that run became a feature coordinator and launched writer `i0scn`. This is a valid division only while the two coordinators avoid editing the writer's worktree. A writer prompt should say to implement the bounded change, not repeat the parent's instruction to delegate implementation. Do not accidentally create another coordinator at every level.

## Wait for evidence, with bounded timeouts

Example of a bounded observation, using placeholders for the selected world and returned run ID:

```sh
strand --workspace "$coord_ws" --timeout 55s await \
  --query agent-run-terminal --param run-id="$run_id" \
  --min-count 1 --timeout-secs 45
strand --workspace "$coord_ws" agent show "$run_id"
```

The client timeout should exceed the inner await timeout. A timeout means the condition has not yet been observed; it is not a failure verdict. Reissue the wait after inspecting relevant progress, instead of tight polling.

- `agent-run-terminal` means stopped or failed, not successful work.
- `agent-run-settled` additionally requires process settlement. Use it before same-session continuation or handing the same worktree to another writer.
- Target completion queries require actual target closure. With `stop-on-complete`, await the run, read its result, and accept the target; awaiting only the open target can wait forever for the coordinator's own act.
- For workflow execution, use `workflow ready` and bounded `workflow await`. Let healthy executor-owned gates run. Advance only the ordinary step or checkpoint for which evidence and the live input contract are available.
- Useful progress is a task note, a delegated child starting, a reviewed commit, a validation result, or a gate transition. A live PID alone is weak evidence. Long validation can be healthy without frequent card mutations.

## Accept, rework, and escalate to Oracle

Keep implementation, review acceptance, and landing evidence distinct. Match the reviewed commit to HEAD, remote/PR head, and the quality marker where the shared workflow requires it. Changed HEAD requires review of the changed range.

Dashboard audit `8502s` returned passing quality and browser checks but four independent P2 review findings. It left the task/feature open with a bounded repair recommendation. This distinguishes a completed audit from acceptance of the product. For a scope-ambiguous finding, ask Oracle for disposition against the actual card contract instead of automatically adding unrelated work or waiving the finding.

On a concrete P1/P2 finding, record the finding and immutable reviewed commit, delegate a bounded repair, await settlement, then obtain the required fresh review. Keep the card open and preserve dependency gates until acceptance and required integration have actually happened.

Escalate to Oracle for a diagnosis or decision when the failure needs technical judgment, ownership is unclear, or repeated attempts do not explain the cause. Give Oracle the exact error, relevant run/gate IDs, current commit, attempted repairs, and a bounded question. The coordinator turns its verdict into the next action; it should not replace that verdict with an improvised acceptance.

Observed example: identity `sfc79` progressed through a Sol documentation fix, an Oracle BLOCKED verdict on a workflow-test observation race, a bounded Sol diagnosis/fix, and a fresh Oracle ACCEPTED verdict `xgt8o` at `5bc5c8be5d7c96252cf6813e27da35806e4fa73b`. The final wrapper passed 436 tests and 3,274 assertions, with the marker and remote/PR head matching. Acceptance still precedes the existing shared landing workflow.

At 17:01 UTC the coordinator recorded successful landing of that same accepted candidate: PR 26 merged as `b1955a96ad91bf2909a407859fca1565ec4b9fdb`, workflow root `3m79c` closed, canonical main updated, feature resources cleaned, and FIFO released. `land-sfc79-k5zd2` lives in the Millhouse workspace even though its feature and coordinator are tracked in Harnesses. Record a workflow's workspace alongside its ID; the ID by itself is not globally addressable.

## Recover failures without losing ownership or evidence

The mill restart lost process custody facts that were held in its lifetime's memory. Runs `ogx4f`, `f90fv`, and `v1fmr` became failed with settlement unknown. Do not manufacture settlement or assume a failed registry entry proves that the old process is gone. Preserve the historical records and examine concrete native/process evidence before any new worktree ownership.

A distinct read-only recovery review was possible after an aborted native session and absence of its exact process were established. It did not pretend the original assignment had resumed or waive the original review requirement.

Passive task notes are not a live steering channel. Recovery worker `k6jgy` continued investigating the original custody loss without rereading a newer primer. A supported stop of that exact _new_ run, observed graceful settlement, and native resume as `129ar` delivered the corrected prompt directly, preserving its session and model. Do not use this as a reason to interrupt healthy work whose current prompt is still correct.

Keep a failed FIFO landing turn while repairing its cause. The recovered `land-vz8a2-k5zd2` demonstrates two bounded poured-request repairs:

- `pull.rebase=true` made a fast-forward pull reject unrelated dirty files. After proving upstream paths and dirty paths were disjoint, the executor's command used explicit `--no-rebase --ff-only`. Nine dirty file hashes and staged state were unchanged afterward.
- Cleanup then fetched a deleted feature branch through a narrow configured refspec. An explicit main fetch repaired that gate without changing repository configuration. The actual workflow completed and released its FIFO lock.

Do not close a failed gate merely to make the board look clear. Repair the request/cause through the supported executor path, then verify its real output and downstream housekeeping. Do not discard, stash, or opportunistically commit another owner's dirty files. Never use process-name kill patterns.

## Reconcile open cards rather than manufacture activity

Old open cards can contain unfinished work, completed but unaccepted outcomes, or designs awaiting a decision. Reconcile each against its declared outcome. Closed child tasks alone are insufficient; missing worktrees alone do not prove that work was lost. Millhouse `wij2z`, for example, has a merged PR whose branch and worktree were already cleaned.

Respect repository migration boundaries without dropping the requested outcome. Archive coordinator `v0zpb` found CLI argument forwarding absent in both the archive and active replacement. Instead of changing archived shipped source or calling the audit completion, it created Harnesses feature `xj0mn`, linked the old card, prepared an isolated worktree, and delegated implementation `ce3cw`. The parent notified the existing Harnesses identity coordinator about possibly overlapping CLI/provider surfaces so integration remains explicit and ordered.

The user subsequently made the boundary stricter: `agent-harness.spool` is entirely deprecated and read-only; all updates and usage belong in `harnesses.spool`. The parent stopped `v0zpb` by exact run ID, verified graceful settlement and absent worker PIDs, and found no process with an archive cwd. An old interactive registry row had no process handle and pointed at Agents; its stop request is recorded without inventing settlement. Do not resume or dispatch work through the deprecated workspace, even for further coordination. The repository instruction correction is feature `wtqou` with Sol/Pi `26lul`. Active replacement `xj0mn` and writer `ce3cw` continue in Harnesses. Parent-owned task `irfb7` explicitly takes over their acceptance/landing supervision and is a real handoff opportunity for the reviewed sub-coordinator alias. Deprecation must transfer the remaining outcome and its supervisor, not abandon the work.

Millstrand coordinator `h1i9g` both launched implementation `are8d` for `1oks3` and closed two completed audit cards after checking their actual evidence. Empty local boards in Devflow and the UI do not need invented tasks or idle workers. Refinement and ambiguous parked designs should receive a concrete decision/blocker, not guessed requirements.

## Questions to resolve before creating the alias

- What exact prompt/target contract should coordinator launches use so task assignments never emit invalid feature-claim instructions?
- How should a parent deliver steering without relying on incidental note reads?
- What observation interval and escalation threshold keep long checks visible without treating slow output as failure or busy-polling the world?
- Which restart/repair permissions belong to a sub-coordinator, and which must return to its parent? The current user granted broad coordination permissions; a future alias should receive explicit permissions for its particular run.
- What minimal completion report proves the coordinator either drained eligible work or left precise dependency/decision blockers and a valid next owner?

The user first deferred alias creation, then explicitly requested live rollout and sub-coordinator delegation during the work to test and refine it. That later instruction controls: implement and review the alias, prove additive live registration in a disposable world, then pilot it at a real handoff boundary and extend to other running weavers. Keep existing workers' settings and ownership intact. Record each pilot's behavior and revise the runbook from that evidence.

The user also clarified the model trial: start the new sub-coordinator role on Luna at max effort, and try Terra at high effort if Luna repeatedly performs poorly after clear guidance. Existing Sol implementation workers remain Sol. Judge actual coordination: bounded waits, ownership, correct workspace, ready dispatch, review/rework, and acting on Oracle direction. Separate model errors from infrastructure failures and ordinary long-running checks. Record specific mistakes, corrective guidance, and the outcome. Do not persevere indefinitely with a model that keeps failing these duties. A model change needs a supported fresh handoff after settlement; native resume preserves prior model/settings.

## User ownership of mill shutdown

The user explicitly required this line in every active repository's `AGENTS.md`: "Never stop the mill; only the user may stop it." This supersedes older broad coordinator permissions to stop or restart the global mill. A restart includes a stop, so agents must not use it as a workaround. Feature `7qxp9`, Sol/Pi coordinator `iabiu`, owns the eight-repository instruction rollout. Preserve existing user edits, especially the dirty Agents and Notes instruction files; the deprecated archive remains read-only and unused.

For reliable delivery to running coordinators, the parent stopped only their exact agent runs, verified graceful settlement, and resumed their native sessions with the user rule in the actual prompt. Implementation children kept running. Current continuations are alias worker `jjrt0`, Millstrand `3qdbe`, Millhouse `56pqt`, and Harnesses identity coordinator `in4r1`; their standard card and task run pointers were updated. Never confuse pausing a coordinator agent with stopping its world or the global mill.

## Review the executable runbook

Alias candidate `3980f78` passed focused tests and aggregate quality but Oracle `rk78g` rejected its Nushell examples: Bash-style backslash continuations were invalid in the advertised shell. Luna reviewer `vrx1i` separately found that the live-registration proof omitted frozen prompt and lineage attributes. Validate rendered command examples in their actual shell, and compare all relevant immutable launch bindings when proving existing runs are unchanged. These are implementation/review findings, not evidence against the new Luna coordinator role, whose live trial has not yet started. Apply the concrete findings, review the new exact commit, and only then deploy the candidate.

## Additive live rollout and initial trials

Oracle `vbi6w` accepted revised alias commit `55e79eb`. Its rendered Nushell examples parse, aggregate quality passes, and the config suite has 6 tests and 88 assertions. The parent loaded an immutable copy of its source blob and called the documented narrow `register!` through each live Weaver nREPL. All eight active worlds now resolve `sub-coordinator` to Pi/Luna max: Codethread, Skein, Millhouse, Harnesses, Devflow, UI, Agents, and Notes. Every existing alias entry and captured run's frozen launch bindings remained unchanged. No world or Mill restart, broad bootstrap reload, flag change, or deprecated archive usage was needed. The Mill remained PID `64448`.

Per-world before/after evidence is stored under `/var/folders/6w/lnly9x394flgz3q7zty955500000gn/T/sub-coordinator-live-20260913-b_uaavy0/`. The source blob's SHA-256 is recorded alongside the reviewed commit in the Harnesses proof. Live availability is staging; shared source landing remains required for durable startup configuration.

Three initial Luna max pilots own distinct slices:

- `vlf1t` / `irfb7`: Harnesses provider-argv review, rework and landing. It preserved Sol writer `16yfb`, observed 45-second timeouts with 55-second outer bounds, checked concrete diff/commit progress, and waited for settlement rather than accepting an active writer's clean pushed commit prematurely.
- `pg0pc` / `sl9o5`: land the accepted alias source. It respected the separate parent rollout task and started shared land without the optional feature card, leaving parent acceptance open. Its PR is Codethread #15.
- `8ccxd` / `7qxp9`: finish and verify the eight-repository Mill ownership rule. Six repo changes had landed before the predecessor `iabiu` was gracefully stopped at a read-only wait boundary. The fresh handoff included every source task/card/run/merge pointer, two remaining checks, and preservation evidence for the dirty Agents and Notes instruction files. Source workers stayed live.

These observations support the initial ownership/waiting gate, not a blanket model-quality verdict. Continue observing review/rework and actual failure recovery. Switch to Terra high only for repeated concrete coordination failures after correction, through a fresh settled handoff with the same runbook.

## Adjudicate review claims against evidence

Shared-land reviewer `2heu9` incorrectly claimed that top-level `strand show` does not exist and recommended `strand agent show`. Landing pilot `pg0pc` forwarded that claim as an established P2; Sol `5wow8` made the two suggested substitutions. Direct readback shows why that proposed fix is wrong: the top-level command returns the raw record with `attributes`, while the agent command returns a summary without them. The original eight-world proof had already executed the raw-record commands successfully.

The parent stopped those two exact runs, verified graceful settlement, and delivered evidence through native continuations. Current source writer is `9bqwu`; landing pilot is `wvdeh`. They must preserve the raw-record calls, clarify the documentation, strengthen the runbook, and obtain Oracle disposition of the disputed finding before accepting a candidate. No incorrect replacement was accepted or deployed. Treat findings as claims to investigate, particularly when they contradict an observed API result. This is the first concrete pilot correction, not repeated model failure warranting Terra yet.

Other pilot evidence remains useful: `vlf1t` waited for the provider-argv writer to settle, then dispatched fresh tracked Oracle `3qenq` for pushed candidate `6680740`. `8ccxd` distinguished repeated 480-second Skein test timeouts from assertion failures and retained a progressing test process while seeking Oracle direction. It separately identified the Millhouse provider boundary as stuck after its 600-second command timeout, with no new session event or child process, and chose exact worker stop/settlement/native continuation. A timeout alone is not that diagnosis; combine elapsed bounds, semantic progress, native events, and process custody before intervening.

## Live availability versus durable startup

All eight live worlds have the reviewed alias, but seven consumer dependency files still pin older Codethread configuration: Skein, Millhouse, Harnesses, Devflow, and UI at `b23d84b`; Agents and Notes at `252eeaee`. Codethread itself uses local roots. Live registration alone would not make a future ordinary start load the new source. Feature `evzz8`, Sol/Pi coordinator `7uxj0`, owns scoped consumer pin delivery. Its delivery task `n4vra` depends on actual source landing `sl9o5`; inventory task `7f2wt` can proceed independently.

Use the actual accepted merge commit, preserve unrelated pins and custom configuration, and prove the consumer setup in a disposable world. Older direct Harnesses overrides in Agents/Notes require compatibility evidence, not assumed compatibility or a broad upgrade. Keep paired Codethread roots at the intended revision where applicable. Serialize heavy checks after the current host-heavy gates drain; repeated timeouts with live progress are a reason to coordinate capacity and seek direction, not to stop the Mill or manufacture a passing gate.

The source refinement after the disputed review is `0f5ca2e`. Oracle `lr0az` invalidated the command finding and found no source P1/P2, but withheld landing acceptance because the required marker still named `55e79eb`. This distinction matters: passing a quality script directly is not necessarily the supported wrapper's exact-HEAD evidence. Landing coordinator `wvdeh` owns refreshing that evidence through the shared workflow, renewed review, and actual source landing.

## Pilot acceptance and ownership wording

Harnesses pilot `vlf1t` completed its bounded provider-argv assignment through shared land, including fresh Oracle acceptance after FIFO rebased the candidate. PR 8 merged as `2b2f40fc4c69f7a3413451f7a3aa271619fd5ad9`; the feature, review, and supervision tasks are closed, the queue is empty, the feature worktree is gone, and canonical main is clean. This is completed pilot evidence, rather than merely a successfully launched coordinator.

Landing pilot `wvdeh` subsequently verified a different review finding against the actual alias catalog: registration replaces an existing entry by name. It commissioned a narrow pre-registration guard repair, preserved the proven raw-record calls, and obtained fresh exact-candidate review. Source continuation `k9294` settled at `a4805c23c0a40d228ae3bfb342715b6e375d0dba`; reviewer `wml9a` reports no P1/P2, with the shared quality marker matching that SHA. Source landing still requires its remaining supported workflow and acceptance gates. This is evidence that the pilot applied the earlier correction, not repeated failure warranting a model switch.

Be precise when handing off run-pointer custody. Preserve the primary writer's ownership role; do not point an implementation feature at its supervising coordinator. Advance that feature's primary run pointer whenever the same writer is natively resumed, preserving the predecessor in a note. A coordinator-owned umbrella feature instead points at its coordinator. Earlier parent wording to "preserve the writer pointer" was ambiguous; distinguish role preservation from leaving a stopped predecessor as the current run before judging model quality.

Millhouse's user-only Mill rule landed through PR 27 as `d2fe9e3808eca872894d9a412a9eeea55fb71cd2`: quality passed 436 tests and 3274 assertions, basic review found no P1/P2, and canonical AGENTS.md contains the exact instruction. Seven active repos now have the rule; Skein remains under the Oracle-directed quieter-window retry. Its check must actually pass before that eighth source task can be accepted.

## Complete instruction rollout and refine the live seat

Skein's authorized quieter-window retry passed the full quality contract, then normal review and land merged PR 473 as `bfce35e1b89dc4b6c14426b56ac0c636373033d1`. The exact user-only Mill instruction is now independently verified in canonical AGENTS.md across all eight active repos: Codethread, Skein, Millhouse, Harnesses, Devflow, UI, Agents, and Notes. The deprecated archive was not used or edited.

Oracle `6beyy` accepted exact alias source `a4805c2`. The parent then refreshed only its own previously registered alias in every active world. Each preflight required an exact match to the saved reviewed `55e79eb` descriptor, followed by a second in-REPL descriptor guard before registration. The parent exclusively owned live registration; delegated scopes excluded it. All eight postchecks proved the intended review-verification paragraph was the only candidate change, other aliases and flags were unchanged, and captured runs retained their frozen launch settings. Evidence is under `/var/folders/6w/lnly9x394flgz3q7zty955500000gn/T/sub-coordinator-refinement-20260913-zcn1aq2_/`. Existing sessions keep frozen guidance; this refinement applies to new runs.

The new guidance is being exercised by pilot `ah23n` (`young-young-stoat`), Pi/Luna max, coordinating durable startup-pin feature `evzz8`. Its predecessor Sol `7uxj0` completed inventory and settled through exact managed cancellation at a bounded read-only await. No pin writer was active. The new feature owner and run pointer were recorded immediately, with predecessor and seven prepared consumer tasks preserved. Its prompt explicitly separates umbrella coordinator ownership from per-source writer pointers and serializes heavy checks.

## Observe automatic workflow boundaries before promising a hold

Shared land rebased the accepted alias candidate to `6e8cf5b`, then its executor merged PR 15 as `3dcf5f051842512d444b9d111d3998f4078081f6` at 18:41:28 UTC. The coordinator's proposed instruction to hold the squash step for fresh exact-SHA review was recorded at 18:41:37, after the merge. A note is not an executor pause. Inspect the actual workflow graph before promising a review boundary between automatically advancing steps.

Keep the evidence chronology honest: prior review of `a4805c2` is not a fresh pre-merge review of `6e8cf5b`. Landing owner `wvdeh` retains task `sl9o5` for exact-range review and Oracle adjudication of the rebased/merged result, with patch/tree equivalence and preservation of the user-only Mill rule. Consumer pins remain blocked until that acceptance is real. Do not fabricate a paused gate, forge a past acceptance, or revert an otherwise valid result merely to make the historical workflow resemble the intended sequence.

## Exercise the refined role and make a bounded fallback

Skein's Sol coordinator `3qdbe` reached a read-only worker await after rebasing the formatting candidate onto the landed user-only Mill instruction. The parent settled that coordinator and handed repository coordination to refined Luna pilot `2gs5l` (`merry-smart-yak`), preserving source writer `ocx6k`, quality gate `43ov6`, reviews and worktrees. Its first observations correctly distinguished native semantic progress from a stale registry timestamp and timed-out await. The umbrella and coordination task pointers were advanced immediately.

The alias landing pilot received a bounded Terra fallback after repeated review-evidence corrections. It had first forwarded a false API review claim, then later cancelled required Oracle `7u47g` as "optional" and ended without its verdict. That Oracle had actual read/bash activity followed by an untracked native subagent wait; `process-phase=starting` did not establish provider failure. The Oracle's opening statement was not acceptance. Record these concrete events without generalizing them into a claim that every Luna assignment fails: the provider-argv and eight-repo instruction pilots completed accepted work.

The fresh fallback is task `negj4`, initially run `1mehk`, Pi/Terra high with the same refined shared runbook. The parent selected the existing fallback candidate only for publication, verified the frozen Terra/high launch, and restored the prior runtime flags. Other worlds and successful Luna sessions were not changed. The fallback must produce an actual required Oracle verdict, retain the honest post-landing chronology, and clean its own audit checkout only after settlement.

## Preflight native continuation and announce ownership first

A verified usable native session does not prove that its frozen working directory still exists. After land removed the source checkout, pin coordinator `ah23n` resumed the settled source coordinator as `pe45i`. The launch failed before any provider started because its retained cwd was gone. It retained a pending process handle and `no-terminal-evidence`; exact stop did not establish settlement. Do not forge that evidence or apply `retry!` blindly: current Harnesses rejects unsettled and request-bound in-place retries. Bounded Sol diagnostic `17u1h`, task `zotlf`, owns read-only recovery/source-contract diagnosis.

Before native resume, check both the reported session eligibility and the actual cwd, required checkout/ref and remaining owner resources. Restore an owned disposable cwd only through a supported, evidenced recovery; otherwise use an explicitly authorized fresh task/assignment in an available workspace. Preserve the original failure record and separate its custody issue from source acceptance.

The parent was preparing its fallback while the pin coordinator attempted that source recovery. The parent should have published exclusive handoff ownership before dispatching. Record this as a coordination race, not a model-quality failure. Once the parent took ownership, `ah23n` preserved the correct pin gate, stopped further recovery attempts, and reported the concrete external blocker.

## Concurrent roles need distinct tracked targets

One active managed run owns a target. The parent's first Terra prompt explicitly required its Oracle to use the coordinator's own `negj4` target; the runtime correctly rejected that concurrent assignment. Terra read the request back, preserved its healthy coordinator and audit checkout, and reported the conflict. That was correct behavior under an over-specific parent instruction.

Create a real reviewer child task before dispatch. Dedicated target `fa2se` now belongs to the required Oracle, while the Terra continuation owns `negj4`. Context records the relationship; it does not replace `--target`. Do not drop the target or stop the healthy coordinator to work around exclusivity. Keep each task's current run pointer and the parent feature's primary ownership distinct.

Also distinguish direct and inherited alias resolution: `sol-high` selects `sol`, which selects Pi. A preflight that requires every alias's immediate parent to be `pi` incorrectly rejects this valid Sol/Pi route. Follow the selected alias chain to the available concrete harness, then verify the published run.

## Accept actual outcomes and keep independent defects visible

Terra continuation `90onc` completed the corrected acceptance assignment. It dispatched required Oracle `1uzjw` against dedicated task `fa2se`, retained the owned audit checkout until settlement, and received explicit **ACCEPT FOR CONTINUED ROLLOUT**, with no P1/P2, for exact `92b4363..6e8cf5b` and merged `3dcf5f0`. The trees match `6d47a66`. It then cleaned its own audit resources and closed only `fa2se` and `negj4`, leaving parent-owned tasks alone. This is an accepted Terra fallback outcome after correcting the parent's target contract.

The parent accepted and closed `sl9o5`, the live-pilot task `ibudy`, and source feature `jxvxu`. Merged source hash `5ed2b8f3ec824af6752b66057eed9129a9936befd923046415f77425b1b9d362` matches the refined alias already proved live in all eight worlds. Pin delivery feature `evzz8` is independently active: native Luna continuation `8djua` resumes `ah23n` in its verified existing cwd with frozen Luna/max settings. Its first source workers are `b2uk1` for Agents task `12vie` and `y8osn` for Notes task `2biz1`, both tracked Sol/Pi, targeting the accepted merge `3dcf5f0`.

Sol diagnosis `17u1h` established that exact `process/malformed-launch` is a typed pre-reservation rejection: Skein validates cwd before reserving custody, and the still-running original Mill has no `pe45i/attempt-1` record. The existing Harnesses crash-window marker nevertheless leaves the run unsettled. Current CLI recovery cannot resolve it, and hand-supplying a custody fact would violate the settlement contract. That old record remains untouched and visibly unresolved; source acceptance does not pretend to settle it.

The narrow forward fix is now Harnesses feature `qlfu6`, epic `8qqa1`, assigned to sole Sol/Pi writer `dgzpl` in its recorded isolated worktree. Only the exact typed malformed-launch rejection may settle as launch failure; control loss and generic/possibly post-reservation errors must remain ambiguous. The assignment requires focused classification coverage and a disposable admitted missing-cwd fixture proving the provider never starts and the run settles. It excludes historical error-string repair, live reloads, shared service lifecycle actions, and the identity epic's active branches. Parent coordinates the `execution.clj` overlap and final review/landing; the implementation is not yet accepted.

## Keep repo execution local and coordinate integration explicitly

The current recovery has local coordinators for Skein (`2gs5l`), Millhouse (`7tx9h`), and Harnesses (native continuation `ml88n`). Codethread's `8djua` coordinates the shared alias pins across seven consumers. That is a mixed topology, not a coordinator in every repository. Report that distinction plainly: source workers in separate worktrees do not by themselves establish independent repo-level scheduling. The parent owns cross-repo compatibility and must avoid becoming a serial review or polling bottleneck.

Millhouse's handoff preserved writer `87d98`, its source checkout and FIFO entry `jeo7o`. The new coordinator has its own durable checkout, so source landing cannot delete its cwd. The writer completed `59504f5` with docs and quality passing; fresh Oracle and CI accepted that SHA before the existing failed gate was retried. The subsequent executor-owned quality process is real capacity use. A failed gate whose process has ended is not: do not block unrelated checks merely because its workflow remains active. Keep the merge reservation and CPU capacity concepts separate.

Consumer proofs found actual compatibility requirements. Notes needed the shared config pin plus Workflow `3132c8f`; Agents additionally needed removal of its redundant local Kanban registration. Oracle bounded those repairs, preserving other pins and registration order. Notes PR3 merged as `ac1e5f7`, and Agents PR5 as `04c4e7e`, through their available normal GitHub path because those worlds have no registered land workflow. Their disposable readiness and alias proofs are evidence for those contracts only.

## A linked checkout is an activation surface

The user subsequently reported that direct `pi` startup in `~/dev/projects/agents` failed with unknown `identity startup`; the live command offered only `bind` and `show`. A read-only audit confirmed the same missing operation in all eight worlds. Global Pi settings load extensions directly from the Agents checkout. Advancing that checkout to the accepted Pi hook therefore activated the caller immediately, while the already-running Weavers retained the older identity implementation. The source acceptance did not establish live compatibility. This was a rollout gap, not merely a missing optional desktop test.

Track at least these separate facts before declaring an integration working:

1. The caller and backend source commits were reviewed and landed.
2. Each consumer resolves the intended compatible dependency combination.
3. The running process has loaded that combination and registered its commands.
4. The actual entry path used by the user succeeds against that runtime.

Alias visibility and general workspace readiness cannot stand in for native identity startup. Treat a source fast-forward as deployment when a user's tool loads directly from that checkout. Arrange compatible backend availability before advancing the caller, or prepare a reviewed transition strategy.

Incident task `wj46k` owns the immediate Pi recovery. Tracked Oracle `i5qoq` is evaluating the minimum supported path; recovery is not yet accepted. Parent steered the existing Harnesses coordinator at a verified read-only wait boundary, settled only `in4r1`, and resumed its existing session as `ml88n`. Independently tracked child reviews and all shared services remained running. Long sleeps prevented timely intake of task notes; use bounded named awaits and read the coordination task after each wait. Prioritize a reported broken user entry point over completing unrelated downstream migration scope.

The incident Oracle found that a supported immutable dependency upgrade requires Weaver replacement; no atomic live loader/registration change was established. Agents and Notes share the `dev` JVM and must align their direct identity pins before replacing that pool. The parent chose the complete backend pin route instead of introducing a temporary root-only legacy hook adapter. That decision raced the coordinator's reading of the Oracle choices; an empty fallback assignment was settled and cleaned before pin writers were dispatched. Put the selected option in the actual continuation prompt when a note may arrive after the coordinator has already made its plan.

The user then explicitly authorized Weaver restarts as needed and reiterated: **never restart the Mill**. This supersedes the earlier Weaver sign-off limit for this recovery. Agents are owned by the Mill, not the Weaver. Preserve Mill PID/start time and process custody while replacing the exact Weaver or known pool, then verify new generations, resolved code, registered commands and actual Pi behavior. Do not keep asking for permission already granted, or serialize unrelated repository work behind the planned Weaver replacement.

The current Harnesses coordinator is `vm5zk`, in the same native Sol session. It owns the urgent separate Agents and Notes pin workers `ml3tv` and `5r9j9`; parent owns the pool replacement and live acceptance. Independent source feature `cb0vb`, Sol `ry2uo`, updates the shared config identity pin for the other worlds. All three pin targets use reviewed Identity `b1955a96`. Source fixture validation remains disposable; verification of the user's real Pi entry path is the operational acceptance after activation.

Pin scheduling supplied another bounded Terra trial. Luna `8djua` continued to describe failed `vs8hh` and old notes about passed `qp1gc` as active capacity, despite corrections. It also described withholding awaits as holding an automatic gate. Parent settled only that coordinator and published fresh `pcjya` on distinct task `veoee`, verified frozen Terra/high settings, and restored the prior runtime flags immediately. Source workers and FIFO reservations were preserved. Terra inspected the actual records: `qp1gc` was already passed, `vs8hh` had no running custody, and Harnesses gate `bnfk8` was already closed. It advanced the real reviewer/sign-off frontier and observed the actual final quality process. Record this concrete scheduling improvement; full consumer delivery still needs its own final acceptance.

## Source acceptance, activation and continued custody

Shared config feature `cb0vb` passed its fresh Oracle and normal review and landed as Codethread PR17, `45a49d5`. Parent then replaced only the Codethread Weaver: PID `64461` became `11489`, generation `ded2b3d7`. Live command help now exposes `identity startup` from immutable Identity `b1955a96`. Mill PID `64448` and its start time remained unchanged. This is successful backend activation in one world; it does not establish that the reported Agents entry point is fixed.

Notes direct pin PR4 landed as `431d8bf` with the original dirty-file manifest preserved. Agents PR6 candidate `5508d47a` independently passed real native Pi mint, recovery, one prompt block and no-write conflict checks in a private backend. Its fresh Oracle accepted it before normal landing began. Both members must be prepared before replacing their shared `dev` pool, followed by an ordinary unmanaged Pi startup from the user's actual Agents directory.

Do not repeat expensive proofs merely because another workflow seat is reading the same immutable change. Carry the accepted evidence forward and rerun only what a changed commit, failed check or new finding requires. On this 10-CPU, 16-GiB host, measured load exceeded 24 with about 10 GiB of swap in use. Admit heavy suites according to actual process custody; keep lightweight review, publication and cleanup moving while an existing suite finishes.

Terra pilot `pcjya` correctly repaired stale-gate scheduling but then exited with remaining consumer delivery unfinished. Its authoritative settlement preceded the Codethread Weaver replacement. Parent verified its durable cwd and resumed the same frozen Terra/high session as `50tho`, with an explicit instruction to continue through healthy bounded timeouts. A final message claiming continued activity cannot substitute for a live run. Record the exit honestly; do not blame the later service replacement or silently recreate lost custody.

## Orphaned interactive runs need a supported lifecycle outcome

The user's four examples remain active after stop was requested because their interactive launchers are gone and cannot submit completion. Harnesses feature `0xq77` now owns the repair, with Sol/Pi `0mvqk` in its own recorded worktree and tracked Oracle direction on task `f265p`. Three examples belong to Harnesses; `p9a8g` belongs to Millhouse. Repository-local inspection matters even when the user lists them together.

The proposed default sweep cadence is 60 minutes. Cadence or age alone does not prove process death. Reconciliation must protect live or idle agents, resumed sessions, reused PIDs and cases with unknown remote custody. Provide a supported inspect/dry-run/reconcile path with idempotent, auditable outcomes. Retain run and identity history while pruning stale active projections. When no exit was observed, represent orphaning or abandonment explicitly; never fabricate an exit code, call a private completion hook as a repair, or edit live database rows. Source work and private fixtures precede applying the accepted operation to the user's actual records. The Mill remains running throughout.

## Live Pi acceptance and the next local handoff

Incident `wj46k` is accepted. Agents PR6 landed as `ae8a97b` after normal review found one hygiene issue: the new identity guard retained an unignored state lock. Sol added the existing Notes convention, `state/`, and proved that an actual startup-created lock left the worktree clean. Fresh focused Oracle accepted `e57a294`; no unchanged broad proof was repeated. Notes remained at `431d8bf`.

The parent replaced the two-member Agents/Notes `dev` pool through the supported Agents Weaver restart. PID `64450` became `74343`; both new generations expose Identity `b1955a96` startup. Actual global Pi `0.85.1`, launched from canonical Agents with managed and identity overrides absent, minted `tidy-merry-bison` for native session `01a09c91-b380-72da-939f-caa6bfac6ae4`. A fresh Pi process reopened the exact session header emitted by Pi and recovered that binding. Another materialized exactly one identity prompt block. These debug checks exited before a model request. Mill PID/start time and existing Agents/Notes dirty-file hashes were unchanged. In Pi JSON mode the diagnostic is on stderr; inspect both streams before treating absent stdout as a product failure.

A managed Oracle initially failed after source rebase because its Pi `0.84.4` registry lacked Astra for the project's newly selected Prose agent. The actual user Pi `0.85.1` did list Astra. A read-only review from the known-working durable coordination cwd accepted the source without substituting the user's model. Record executable/version/catalog differences as launch compatibility evidence; do not infer that the user's selection is wrong from one worker environment.

Native resume has another preflight: the original target must remain eligible. Repair continuation `mvx63` inherited closed target `imz0v` and never launched. It was honestly settled as never-launched; fresh Sol `fgott` used open repair task `e5gvx`. A prompt naming a different task does not retarget a frozen run.

The remaining activation feature `k3mob` now uses local coordinators: existing Skein `2gs5l` and Harnesses `vm5zk`, plus Terra/high through Pi in Millhouse (`e1ow1`, continued as `tg8xs`), Devflow (`f90jw`) and UI (`ba2wv`). Each new seat has a durable coordination checkout and delegates a separate Sol source writer. Codethread tracks dependencies and evidence; each seat owns its repository's source, FIFO and permitted Weaver activation.

A zero-byte shared landing acquisition file blocked one coordinator's worktree setup. Preserve the file and its inode; adding its exact generated path to local Git `info/exclude` is a reversible way to satisfy checkout hygiene without removing a shared lock or changing tracked source. Devflow handled this itself; Millhouse unnecessarily escalated and exited, so the parent supplied that narrow metadata fix and resumed it. The same model can make different operational judgments: record the concrete failure and correction rather than declaring a whole model good or bad.

Millhouse then detected a Sol writer invoking an untracked native Pi scout, stopped the exact writer and resumed direct implementation. Follow up on actual child-process custody as well as the parent run. Explicit Strand-only delegation must reach the source worker's prompt; globally available helper tools do not grant permission to use them. Do not count an unauthorized helper's review as acceptance evidence.

## Preserve completion compatibility during a live upgrade

Harnesses source and unmanaged Pi tests passed before replacement, but two already-running managed invocations had been created by the old backend. After replacement, cancelling `8e3c4` and observing exit zero from `0mvqk` both retained honest process settlement while recording a missing-reservation reconciliation failure. Their native continuations were unavailable. Track this as a focused compatibility repair (`b9jg1`), preserve their source artifacts, and use fresh eligible targets when needed. Do not fabricate reservations, rewrite their outcomes, or restart the Mill to make the error disappear.

Deployment acceptance must include an old serialized run completing under the new backend as well as a fresh user session. A passing fresh-start proof cannot establish this boundary. Keep strict validation for new protocol invocations; a legacy compatibility path must be selected from retained protocol evidence.

Put direct-only delegation constraints in each actual review dispatch, including its appended system instructions. A parent policy or an earlier note did not prevent the resumed Oracle from calling native helpers. Check process custody and exclude their evidence if this occurs; preserve independently useful source findings and require direct verification before acceptance. A helper's apparent zero exit can still wrap a REPL reader error, so read the semantic result.

## Require continuity from the coordinator seat

Terra successfully delivered the bounded Devflow discovery correction and Skein's activation and timestamp repair. However, Skein `nq6br` finalized with its review still pending. Resumed `idjf4` received an explicit instruction to continue through acceptance, launched a policy writer, then finalized after one 45-second timeout while that writer was running. Its honest exit zero describes the coordinator process; it does not mean the coordination task was fulfilled.

After the second occurrence the parent selected a fresh Sol/high coordinator, `x6qtq`, on open task `0yu6i`. A frozen Terra continuation would retain its model and original target. The parent updated the feature's ordinary owner/run pointer and closed predecessor coordinator tasks as superseded, preserving their notes and every source worker. This changed one failing seat based on observed behavior.

The loop needs an explicit exit condition: after a bounded wait, inspect current run status, latest task notes, source progress and workflow readiness; then act or await again. Finalize only when eligible work is accepted and cleaned or each remaining item has a concrete blocker, with no child or workflow still needing the coordinator's next action. A list of active children is a handoff only when another coordinator has actually accepted ownership.

## Transfer checkout ownership before cleanup

The parent launched Harnesses compatibility work from a scratch repository that the local coordinator still considered disposable after its earlier feature. That coordinator removed the scratch root while the new linked source worktree was active, breaking its Git metadata. Notes asking for retention did not create an acknowledged ownership transfer. The source files were backed up and the checkout recovered as a standalone repository; the worker later committed and pushed its candidate. The parent takes responsibility for reusing a checkout whose cleanup owner had not accepted the handoff.

Before sharing such a root, establish one cleanup owner and record all dependent worktrees and active runs. Prefer a new worktree rooted in the canonical repo. Before removal, recheck current ownership and Git common-directory dependencies, not just the completed feature's old notes. Preserve source and backups until acceptance. Recovery that changes a linked checkout into a standalone repository must also trigger a landing-layout check: the configured feature branch and canonical main need to satisfy the shared workflow's normal contracts.

A separate review race was stopped by the target reservation: local `vm5zk` already had direct Oracle `121ja` running when the parent attempted another publication. The parent inspected and adopted that review, explicitly handed the whole compatibility feature to the local owner, and removed only its unused extra checkout. Reservation rejection is useful coordination evidence; it does not justify another reviewer on a different target.

## Verify an operation before retrying its checker

The first Sol alias registration succeeded and every preservation check passed, but the parent verifier then read compact `agent list` fields from the different `agent list --full` shape. The full entry places model and effort in its selected candidate. An independent compact read confirmed Pi/Sol/high. The parent retained the failed check, added a correction with both shapes, and did not register again. Evidence lives in `sub-coordinator-sol-millhouse-20260913T235813Z` under the shared evidence directory. Classify the failed assertion before repeating a mutation.

Likewise, direct Oracle inspection may use a write tool for permitted disposable probes or reports. Check the actual path and action; a tool-name allowlist alone does not distinguish an allowed temporary fixture from a prohibited source edit or helper launch. Millhouse direction `exvpk` wrote only its own disposable probe and report files, and its direct evidence remained valid.

## Keep the coordinator mailbox in the wait loop

Harnesses acknowledged root's proposed operator split nineteen minutes after the initial note. Its public trace showed useful source monitoring and bounded awaits, but it checked the child process and dialogue without consistently reading its own coordinator task. Root had promised to wait for the operator acknowledgement, so the missed mailbox delayed live activation while independent source work continued. This was a communication defect in the loop, not evidence that the source worker had stalled or permission from silence.

Read both the coordinator task and active child task after each bounded await. An acknowledgement belongs on the coordinator task and must state the accepted scope and any operation already in progress. Do not stop a productive worker or replace an agent just to deliver a note. Keep acting on independent work until the conflicting ownership is resolved. For an urgent handoff, place a concise pointer on the child task as well; never treat that pointer as an acknowledgement.

## Prove that a Weaver replacement preserves workers

After the explicit handoff, root activated accepted B9 source `10279584` using only the supported Harnesses Weaver replacement. Before the operation, `managed-legacy` was absent from the live runtime. Afterward it was loaded from canonical Harnesses, with a resource hash matching the accepted Git blob. The old and new dependency fingerprints were identical: local source activation required the generation and loaded-resource evidence.

The snapshots also establish the process boundary directly. Both Pi launcher roots retained their PIDs, start times and parent Mill PID `64448`; their children and all captured run attributes stayed unchanged. The new Weaver could read the same running Mill-owned handles. Read-only legacy validation of coordinator `vm5zk` passed and left its row unchanged. This does not claim a completion callback: that requires the real eventual provider outcome. The four historical Stopping rows remain unchanged until the supported orphan operation is accepted and installed.

Evidence: `/Users/ct/dev/evidence/harnesses-b9-activation-20260914T005138Z`. The old Weaver was `35590`; the replacement is `84892`, generation `d94cf791-05f9-4d91-820f-cba24322a1f1`. All other observed Weaver generations and the original Mill process stayed unchanged.

## Accept a real dependency handoff

Skein's Sol coordinator completed its source features, ordinary quality and review gates, FIFO cleanup and installed readback. Its final audit accepted the bounded outcome. The remaining P2 cards depended on parent-owned consumer pins and the active Millhouse feature, so root explicitly accepted those dependency waits and retained the coordinator checkout. Normal finalization at that boundary is appropriate: there is no local child or gate awaiting its next action.

This differs from the earlier Terra exits while reviews or source children still needed an owner. Record the exact remaining card, prerequisite, owner and event that makes it actionable; then obtain an acknowledged handoff. Do not keep an agent polling an unchanged dependency merely to display an active run. Do not mark the dependent source feature complete. A later continuation must use an open target and an existing checkout.

The first Sol alias pilot also reached a useful rework boundary without parent intervention. `0pln0` consumed direct Oracle `rlhh6`'s single finding, reopened source task `3snhl`, and resumed the original Sol writer as `3xbhq` with the exact regression. It preserved the source worktree, review dependency and queue contracts. This proves a sustained await-to-rework handoff; final acceptance and durable alias rollout still need their own evidence.

## Distinguish a ready run from an eligible target

Harnesses follow-up Oracle `envk9` was published without an attempt because its review target depended on implementation task `7i8o9`, which remained active after the writer had finished. Root verified the exact clean pushed candidate and checks, closed only that completed implementation milestone, and the same Oracle started. No edge removal, duplicate review or fabricated callback was needed. Implementation completion is a legitimate prerequisite for review, not a claim that the review or feature has been accepted.

The reverse happened after that review found another race. Source continuation `u28ht` was published against the now-closed implementation task and waited ten minutes without custody. Root reopened the same milestone for the documented unfinished repair, updated the ordinary run pointer, and the same published run started immediately. Sol coordination needs the same explicit lifecycle checks as the other tested models. A genuinely completed coordinator or review target still needs a fresh task; a new finding on the same source milestone can legitimately reopen it for its retained writer. Alias follow-up `mbygj` captures both cases and the coordinator-plus-child mailbox check after every await.

## Freeze review constraints before dispatch

PR19's generated review started before root added direct-only instructions to its gate. The running invocation retained only its original frozen prompt and used an untracked native helper. Updating gate attributes afterward did not change that invocation. Root stopped the exact reviewer, preserved the already completed helper's history but excluded its evidence, and dispatched one fresh direct basic review with the constraints in both its prompt and appended system instructions. That review found a real request-key example error; the original source writer corrected it. Preserve the valid source and quality evidence while replacing only the invalid review.

Check actual public tool actions before accepting a direct-only review. When inspecting a tool result, select only the needed public fields: helper results can embed entire transcripts, including private reasoning, in their details. Never dump those nested messages into the coordinator's evidence.

## Make identifiers and launch context unambiguous

Use exact IDs as separate tokens in prompts. Root's cramped phrases such as `task480r1` and `featurembygj` caused a reviewer to query those nonexistent IDs before finding `480r1` and `mbygj`. Write “task `480r1`” and “feature `mbygj`”. Use distinct placeholders for a predecessor request and the new continuation's stable idempotency key; equivalent retries reuse a key, different requests do not.

Agents' adapter writer could not start because an optional project Pi agent referenced a model absent from that runtime's catalogue. `--no-approve` did not suppress this policy validation. The local coordinator launched a fresh tracked worker from a clean retained driver, with all source operations explicitly targeting the feature worktree. Record driver cwd and source cwd separately, read source instructions explicitly, and retain the driver for native continuations. This is a bounded launch workaround, not a claim that ordinary Pi startup in that project has been repaired.

## Preserve handoffs across a provider quota interruption

A newly published run is not proof that a successor is ready. Harnesses `5m0rp` failed before acknowledging any custody; the predecessor retained ownership until it too naturally settled. Preserve every unfinished task, source checkout and native session, distinguish provider failure from source completion, and inspect the actual run error even when the process reports exit code zero. After an authoritative same-account usage check showed renewed availability, one bounded continuation established real work again. Do not redeem credits, change accounts or spin new request IDs without authorization.

A settled legacy run can provide the missing live-upgrade completion evidence while its task remains unfinished. Record the authentic terminal callback and session usability, close only the compatibility acceptance milestone, and transfer the incomplete source work explicitly to the next coordinator.
