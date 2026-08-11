---
name: millstrand-spools-docs
description: >
  Write and restructure Millstrand spool documentation across the root index,
  handwritten contract and cookbook, public docstrings, and generated API.
  Use when adding a spool, changing its public contract, moving examples to
  docstrings, or auditing what activation publishes.
---

# Millstrand spool documentation

## Prerequisites

- Identify the spool root and source files used by the API-doc generator.
- Read the root README, spool contract, cookbook, public docstrings, and relevant
  tests in full.
- Confirm the repository's API generation, MkDocs, and quality commands.

## Knowledge

### Surface ownership

| Surface | Content |
| --- | --- |
| Root `README.md` | Spool index, documentation links, and complete `spools.edn` coordinate. |
| Spool `README.md` | Smallest consumer contract: activation, authoring model, non-obvious safety rules, and consumer-visible Millstrand surfaces. |
| `<spool>.cookbook.md` | Compositions and trade-offs involving multiple APIs or systems. |
| `<spool>.api.md` | Generated signatures, request/return details, and focused examples from public docstrings. |
| Source only | Executors, atoms, caches, latches, RNGs, private lifecycle machinery, and other implementation details. |

### Contract pattern

Number every H2. Activation is first; Millstrand state and APIs is last. Add only
the domain sections needed between them.

````markdown
# Millhouse Cron spool

`millhouse.spools.cron` publishes fixed-interval jobs over Millstrand's durable
scheduler, with optional jitter and reloadable handlers.

## 1. Activation

```clojure
(runtime/module! runtime :millhouse/cron
  {:ns 'millhouse.spools.cron
   :spools ['millhouse.spools/cron]
   :required? true})
```

## 2. Jobs

`defjob` collects one owner-partitioned job declaration. Explain only the rules
needed to author jobs safely.

## 3. Millstrand state and APIs

| Surface | Identity | Consumer contract |
| --- | --- | --- |
| Job authoring | `defjob` → `:millhouse.spools.cron/jobs` | Publishes one desired job declaration. |
| Durable timing | Scheduler wake `cron/<id>` | Holds the authoritative next-fire time. |
````

Do not add an identity table that repeats the activation form. Do not add a
contract callout, `Examples`, or `See also`.

### Placement examples

| Content | Destination |
| --- | --- |
| `defjob` omission cancels the owner's job | Contract |
| Handlers must tolerate at-least-once delivery | Contract, one sentence |
| Exact `register!` keys, return, and example | `register!` docstring → API |
| In-flight counter and execution pool | Source only |
| Cron + external lock + Kanban card | Cookbook |
| Git coordinate and selectable roots | Root README |
| clj-kondo mapping needed for `defjob` | Final contract table |

Delete prose that expands a clear namespace or Var docstring. A small spool
should have a small contract.

### Millstrand surface rows

Use only consumer-visible registrations, durable identities, attributes, and
tooling. Examples:

```markdown
| Surface | Identity | Consumer contract |
| --- | --- | --- |
| CLI operation | `kanban` from `defbin` | Publishes the board command tree. |
| Named query | `kanban-pending` from `defquery` | Selects pending cards. |
| Workflow | `publish-spool-kondo` from `defworkflow` | Startable workflow. |
| Executor | `shell` from `defexecutor` | Claims ready `:shell` gates. |
| Durable state | `workflow/role` on strands | Identifies graph roles. |
| Tooling | `resources/clj-kondo.exports/...` | Models authoring macros. |
```

Do not inventory private machinery, runtime-local state, or public Clojure Vars
already listed in generated API docs.

### Docstring example

Put a focused call beside its owning Var:

````clojure
(defmacro defjob
  "Collect one cron job declaration for the current runtime module.

  ```clojure
  (defjob :nightly-report
    {:interval-ms 86400000
     :jitter-ms 3600000
     :handler 'my.jobs/emit-report})
  ```"
  ...)
````

Regenerate the API; never copy this basic call into the contract or cookbook.

### Read the API as one document

A documentation restructure is an API editorial pass, not just a generation
check. Public docstrings were often written one Var at a time while working in
code; `*.api.md` reveals how they read together.

```text
contract/cookbook → source docstrings → generate API → read all of API → tighten → repeat
```

Read the complete generated namespace page after moving prose or examples. Check
that the namespace introduction establishes shared concepts, terms stay
consistent across Vars, focused docstrings do not contradict one another, and a
reader can understand each public surface without reconstructing missing context
from source. Move more focused detail from contract or cookbook into docstrings
when the full API exposes a gap.

### Cookbook example

Recipes demonstrate a composition, not one function:

````markdown
## Coordinate many weavers with a best-effort lock and durable card

**Situation.** Every weaver publishes the same expensive job.

**Composition.** Combine Cron jitter, shared locking, and Kanban card creation.

```clojure
(defn scan-tick [runtime]
  (with-best-effort-lock
    #(when-let [finding (scan!)]
       (raise-card! runtime finding))))
```

**Why this shape.** Jitter reduces contention; the durable card is the alert of
record.
````

## Procedure

1. Read every surface and the tests supporting behavioral claims.
2. List primary authoring forms and consumer-visible registrations.
3. Classify existing content using the placement table above.
4. Write a terse H1 introduction and minimal numbered Activation section.
5. Remove identity prose already visible in activation.
6. Keep only domain rules needed to use the authoring forms safely.
7. Move focused calls, concepts, and low-level contracts into owning source
   docstrings.
8. Generate the API and read the complete generated document, not only the diff.
9. Fix contradictions, unexplained terms, poor ordering, and gaps in source
   docstrings; regenerate and reread until the API works as one document.
10. Tighten the contract and cookbook again after the API absorbs their focused
    material.
11. Remove single-function cookbook recipes; retain real compositions.
12. Finish the contract with consumer-visible Millstrand surface rows.
13. Update the root README only if identity, available docs, roots, or coordinates
    changed.
14. Rerun generation to prove idempotence, then run docs, relevant tests, and the
    repository quality command.

## Constraints

- Never hand-edit generated `*.api.md` files.
- Never change runtime behavior merely to simplify prose.
- Never duplicate basic usage across contract, cookbook, and API.
- Treat generated API prose as a document to edit through source docstrings, not
  as an artefact to glance at after generation.
- Activation is the first numbered contract section.
- Millstrand state and APIs is the final numbered contract section.
- Runtime-local implementation state stays out of consumer contracts.
- Every guarantee must agree with source and tests.

## Validation

- [ ] Every H2 is numbered; Activation is first and Millstrand state and APIs is
      last.
- [ ] No identity table repeats activation.
- [ ] Every contract paragraph adds information not obvious from API or cookbook.
- [ ] Final rows describe consumer surfaces, not implementation or a Var list.
- [ ] Focused examples come from docstrings; API generation is idempotent.
- [ ] The complete generated API was read and has no contradictions, unexplained
      shared concepts, or obvious context gaps.
- [ ] Cookbook recipes are compositional.
- [ ] Markdown links, strict MkDocs, relevant tests, and full quality pass.
- [ ] Diff contains no unrelated generated or behavioral changes.
