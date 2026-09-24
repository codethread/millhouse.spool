# Retire the old Kondo exposure card

Reviewed 2026-09-13. Original card: `dce5t`, **Expose Millhouse Kondo workflows through Codethread**. Investigation: `a4jme`, under epic `ppfio`.

**Recommendation: close the original card as superseded by the deps-native migration. Its old implementation plan is no longer needed.** Keep package-owned exports and the ordinary consumer import step. Keep `publish-spool-kondo` as optional publisher guidance in Millhouse. No replacement Kondo implementation card is justified by the evidence collected here.

The original card remains pending; this report records a recommendation, not an assertion that all of its historical acceptance criteria were delivered.

## What now works

Your resource-packaging hypothesis is correct. Millstrand and the macro-owning Millhouse roots—Chime, Cron, and Workflow—include `resources` in their own `deps.edn` `:paths`. Their exports live under `resources/clj-kondo.exports/<org>/<library>/`. Resolving those dependencies makes the exports available on the consumer classpath. Codethread does not need to own or redistribute them. See the [Clojure paths contract](https://clojure.org/reference/deps_edn#paths) and the [Kondo export/import contract](https://cljdoc.org/d/clj-kondo/clj-kondo/2026.08.04/doc/configuration#exporting-and-importing-configuration).

Three distinct steps matter:

| Step | Current mechanism | Consequence |
| --- | --- | --- |
| Make package resources available | Each dependency root declares its own `:paths` | tools.deps supplies the resolved classpath. |
| Import lint support | Run Kondo with `--copy-configs` against that classpath | Copied configs then load during ordinary lint; refresh them when dependency exports change. |
| Activate runtime workflows | Explicit `runtime/module!` and provider `use-*!` selections | A dependency alone does not activate a Millstrand module. |

The minimal consumer import is:

```sh
mkdir -p .clj-kondo
clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint
```

Run it from the consumer root with the intended dependency aliases if needed. Dependency analysis with `--dependencies` can additionally populate Kondo's cache. Neither import nor analysis needs a Codethread runtime. Codethread's config package includes Workflow; consumers using Chime or Cron must also include those distinct roots.

## Why the card is obsolete

The card dates from August 11 and requires `publish-spool-kondo`, `bump-spool`, and `bump-millstrand`, with Codethread as their mandatory exposure point.

The decisive changes were explicit design changes:

- Millhouse [`f487eb42`](https://github.com/codethread/millhouse.spool/commit/f487eb42ea9523e8bd405e64a7c319013217d988), August 30, migrated to ordinary tools.deps roots and deliberately removed the manifest bump workflows while retaining `publish-spool-kondo`.
- Codethread [`356841d`](https://github.com/codethread/codethread.spool/commit/356841d810cac6408cc4fb3cf6cca0094562d28e), August 30, replaced its manifest with dependency roots and explicit activation.
- Codethread had already removed its duplicate `spool-bump` in [`26fdf69`](https://github.com/codethread/codethread.spool/commit/26fdf691649e8c92ae6366c627e0a77cd8219549).

The shared [Codethread bootstrap](https://github.com/codethread/codethread.spool/blob/1dfbc9361db70da54961aaa1740133acb802076c/spools/config/src/ct/spools/codethread/bootstrap.clj#L10) activates the base Workflow engine. Each standard workspace then selects Millhouse's provider module explicitly; see [Codethread startup](https://github.com/codethread/codethread.spool/blob/1dfbc9361db70da54961aaa1740133acb802076c/.millstrand/init.clj#L12) and the [owner selector](https://github.com/codethread/millhouse.spool/blob/114ccb0f870c60eeebfaf60739aabd21f6413f7f/spools/workflow/src/millhouse/spools/workflow/spool.clj#L25). This selector publishes the surviving publisher checklist.

I checked the live workflow catalogues in Codethread, Millhouse, and Millstrand with `strand --workspace <repo>/.millstrand workflow list`. All three already expose `publish-spool-kondo`; neither retired bump workflow appears. These were read-only catalogue checks, not release-workflow execution.

| Original requirement | Recommendation |
| --- | --- |
| Owner-published macro hooks/configs | Keep; resource packaging already works. |
| Three Kondo/bump workflows | Retire; two were intentionally removed. |
| Mandatory central Codethread exposure | Drop; direct owner activation already supplies the surviving workflow. |
| One clean resolved-classpath import | Keep as the consumer contract; verified in this investigation. |
| Cross-repository release runs and final-main quality | Relevant to future dependency or release changes; unnecessary to decide this card's relevance. |

## Independent review and execution evidence

Terra investigated packaging/import in run `nyhq8`, with correction run `o378g`. Sol investigated architecture, pins, and history in run `b6b10`. I reviewed their decisive source references and commits, then independently repeated the clean-consumer experiment. Full agent reports are [Terra](terra.md) and [Sol](sol.md); exact dependencies, fixture, commands, and outputs are in [coordinator-reproduction.json](coordinator-reproduction.json).

The reproduction used `clojure -Srepro -Spath` and `clj-kondo --repro` v2026.08.04 in a disposable directory, with Millstrand `310368dff9174bd889ad21d4ed8196952684eaf9` and Millhouse `f487eb42ea9523e8bd405e64a7c319013217d988`. These are pinned revisions, not local source replacements. Chime and Cron were added explicitly to exercise their exports alongside Workflow and Millstrand.

One import copied all four owner configurations. The corrected fixture tested Millstrand operations, Chime rules, Cron jobs, Workflow definitions, and Workflow executors, including inert declarations, bang declarations, and their use forms. **Result: exit 0, zero errors, zero warnings.** There were no local macro mappings or hook overrides.

The review rejected a false positive in Terra's first report. Its fixture used unsuffixed rule and executor names. Chime actually generates `<name>-rule`, and Workflow generates `<name>-stalled?`; the hooks model those names correctly. Changing only the four use arguments eliminated all four reported errors. The corresponding authoring plans are [Chime](https://github.com/codethread/millhouse.spool/blob/114ccb0f870c60eeebfaf60739aabd21f6413f7f/spools/chime/src/millhouse/spools/chime.clj#L86) and [Workflow](https://github.com/codethread/millhouse.spool/blob/114ccb0f870c60eeebfaf60739aabd21f6413f7f/spools/workflow/src/millhouse/spools/workflow.clj#L1015). There is no demonstrated hook defect to turn into a replacement card.

## Separate maintenance worth considering

Millstrand's workspace directly pins Workflow to `3604419e406b3c426acd738bc2f0033cdb4edb25`, diverging from Millhouse main: one commit unique to that pin and five unique to main at review time, with differences in 14 Workflow files. Codethread pins the earlier main ancestor `f487eb42`. A small dependency-maintenance task could explain or align that divergence if consistency is desired. Age or divergence alone does not establish a Kondo defect, and adding a Codethread facade would not override a consumer's direct dependency choice.

The current [Codethread quality script](https://github.com/codethread/codethread.spool/blob/1dfbc9361db70da54961aaa1740133acb802076c/scripts/quality.sh#L1) does not refresh imported Kondo configs. Treat import refresh as an explicit setup/dependency-update responsibility; automate it only if that is a recurring maintenance problem.

This investigation did not change implementation, pins, shared weavers, or releases, and did not run every repository's full quality suite. The static smoke covers the listed macro families, not every possible API form. Those limits do not prevent retiring a card whose core workflow design was explicitly superseded.
