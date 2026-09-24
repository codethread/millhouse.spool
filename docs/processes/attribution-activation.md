# Attribution dependency activation

> Historical rollout record: paths, pins, and board states below describe the
> pre-consolidation ecosystem, not the current source workflow. For new work use
> [consolidation and handoff](../consolidation.md) and the owning workspace
> policy. No live activation is authorized by this record.

This procedure activates the breaking identity-attribution convention in a consumer. It is deliberately split into source acceptance, runtime deployment, and UI rollout. Passing the disposable checks below does **not** change a running Weaver.

## Accepted source basis

Use these immutable producer revisions together. Each Tools.deps root remains independent; do not replace a published coordinate with a sibling checkout.

| Component | Revision | Purpose |
| --- | --- | --- |
| Millstrand core | `8e220eab7de2fabe7880c6a4c71de6cd903c34bb` | Core graph and runtime API basis. It has no Millhouse Identity dependency. |
| Millhouse | `bd96f5357a335bd17cd22042da1be5bd2200f807` | Identity, Kanban, Workflow, and Land roots. It contains accepted identity feature `f17ad387b2825887b736cab597b33af84cff13cb`. |
| Harnesses | `6b5ad39d8711a033dc7f33fd52c78901393ea44e` | Managed-run attribution, assignment, executor, and continuation records. |
| Codethread config | `51a8237f849f94f6eed55e0721c77cda2086c703` | Bootstrap ordering and ownership-aware auto-run consumer contract. |

The Codethread config revision is the minimum runtime source basis. The consumer-smoke delivery that publishes this document has its exact candidate SHA recorded on its PR and Kanban card because a commit cannot contain its own SHA.

## Source acceptance checks

Run these in a source checkout or disposable test world. They do not authorize or perform live deployment:

```text
(cd spools/config && clojure -M:test)
make quality
(cd spools/config && clojure -M:consumer-smoke \
  /path/to/millhouse.spool /path/to/codethread.spool \
  /path/to/harnesses.spool /path/to/devflow.spool /path/to/consumer)
```

`clojure -M:test` runs the provenance smoke using the accepted Git coordinates above for Millstrand, Millhouse, and Harnesses, a disposable SQLite Weaver, and a small consumer module. The separate `-M:consumer-smoke` command verifies the shared landing surface of a checked-in consumer. The provenance smoke proves all of the following from durable graph records:

- an unowned card can retain a reporter;
- an unresolved note actor remains valid and resolves after a later local identity registration;
- A -> B -> A creates three immutable claim records and the unresolved final A remains current;
- delegation and its continuation retain `serves`, `serves-root`, and logical run lineage; and
- a user workflow actor (`identity/by-identity`) remains distinct from trusted executor provenance (`workflow/executor` and executor run ID).

Repeated attribution reconciliation must report zero writes after convergence. A cold disposable restart must rebuild reporter, claim, and actor projections from the records. Never accept scalar `owner`, a latest note, or a run ID as a substitute for this graph history.

Confirm dependency resolution rather than relying on a previously loaded classloader:

```text
(cd spools/config && clojure -Spath)
(cd .millstrand && clojure -Spath)
```

Check that every selected Millhouse root is `bd96f5357a335bd17cd22042da1be5bd2200f807`, Harnesses is `6b5ad39d8711a033dc7f33fd52c78901393ea44e`, and Millstrand is `8e220eab7de2fabe7880c6a4c71de6cd903c34bb`. A `:local/root` is acceptable only inside a disposable test world. Core remains independent: it must not gain a `millhouse.spools/identity` coordinate merely because consumers select the Identity root.

## Operator activation order

1. **Accept source.** Merge reviewed producer and consumer commits, then update each consumer's independent dependency roots to the compatible revisions. This is source delivery only; record no deployment evidence yet.
2. **Inspect before action.** On each canonical workspace, use current help:

    ```text
    mill weaver list
    mill weaver status --json
    strand help kanban claim
    strand help kanban note
    strand help workflow complete
    strand help identity reconcile
    ```

    Expected public changes are `--owner` for the explicit claim owner and `--by-identity` for note, workflow, and agent actors. The persisted wire key is `identity/by-identity`. `kanban card` must project `reporter` and ordered `ownership.current` / `ownership.history`; `strand identity attributions` must show `resolved`, `unresolved`, or `ambiguous` source diagnostics.

3. **Obtain deployment authorization.** A dependency-pin change requires the supported Weaver restart procedure and explicit operator approval. Do not stop Mill, stop/restart a Weaver, alter a live dependency basis, or use a classloader workaround from this procedure.
4. **Activate during the approved window.** Use the live supported restart command discovered in step 2 for one canonical workspace at a time. Preserve existing accepted Harnesses runs. Source-only module edits may use normal refresh; they are not a pin activation.
5. **Verify the newly loaded runtime.** After the operator-authorized restart, inspect:

    ```text
    strand help merge-queue
    strand workflow list
    strand workflow show land
    strand auto-run status
    strand kanban card CARD_ID
    strand identity attributions SOURCE_ID
    ```

    Confirm the configured module order is Identity and Workflow, then Kanban, then Harnesses; activate the agent executor only after consumer aliases, workflows, and policy reconciliation. `auto-run status` is an admission receipt, not proof that an old or new worker is running.

## Consumer inventory and follow-ups

Current source discovery found these real consumers of the breaking Codethread config surface:

| Owning repository | Current evidence | Required follow-up |
| --- | --- | --- |
| `millhouse.spool` | `.millstrand/deps.edn` pins Codethread config at `4e920fa712d059d73a2a156a46b4686f0833db67`; `.millstrand/me/auto_run.clj` imports the dispatcher. | Owning-board follow-up `wm227`: update the Codethread config pin and verify its canonical workspace after operator approval. |
| `millstrand-ui` | `.millstrand/deps.edn` pins Codethread config at `9390164b204ceedbf025897c2fdedcb4acc823f1`; its init activates Codethread bootstrap and auto-run. | Existing owning-board feature `z6djg`: update compatible core/config pins and perform separate UI rollout verification after runtime activation. |

Create and track these follow-ups on their owning boards. The old `agent-harness.spool` is deprecated and excluded: do not inspect, modify, or create work there.

## Known old-data limits

The reconciler enriches evidence that already exists. It does not manufacture old reporter values, claims, handoffs, actor strings, or `performed`/`serves` relations. Legacy scalar `owner` attributes and historical notes are not a claim history. Ambiguous or absent identity records leave valid raw attribution unlinked and inspectable; they do not reject an otherwise valid work mutation.

## Deployment and UI status

- **Source acceptance:** verified by disposable tests and reviewed commits.
- **Runtime deployment:** open until an operator authorizes and completes a supported restart for each canonical Weaver.
- **UI rollout:** open until `millstrand-ui` consumes the compatible pins and verifies its UI against an activated runtime.
