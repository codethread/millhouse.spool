# Codex coordinator launch policy

Apply this file as launch-time appended guidance, in addition to the generic alias runbook and bounded assignment. This is the Codex default timing policy, not a claim about frozen older sessions or an automatically configured CLI timer. A harness/agent-specific override must explicitly name its reason, inner wait, client deadline and outer-tool behavior in the launch and coordinator task note.

Keep the headed Codex process in the assigned repository's canonical root. Establish a real goal using the goal facility exposed by this Codex installation; verify public goal text/state and ID where exposed. Do not translate Pi `/goal` syntax into an assumed Codex command. Record managed session and actual native thread/session separately if they differ, with the tracked run and terminal.

Default owned-work observation is an event-aware **180-second** Strand await. Use `strand --timeout 210s await ... --timeout-secs 180`, or `strand --timeout 210s workflow await RUN --timeout-secs 180`. Set the outer execution-tool deadline to at least 240 seconds where supported. Positive events may return early: handle them immediately, not after an unconditional sleep.

If the Codex command tool yields asynchronously before the wait ends, retain and await that same command handle using a supported long yield; do not publish new remote awaits or repeatedly poll every few seconds. A short tool yield is not a cadence override. After actual completion/timeout, read compact owned work and the relevant current run and coordinator/child task notes, then act or reissue.

Never substitute `goal_wait` for owned Strand worker/review/workflow progress. Terminal, settlement and accepted work remain different evidence. Healthy validation and a normal timeout alone do not justify escalation or replacement. Finish the goal only when required Kanban outcomes are accepted or an explicit handoff is acknowledged. Never stop or restart Mill. Use tracked Strand only for delegation; never native collaboration helpers or untracked threads.
