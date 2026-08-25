(require '[quickdoc.api :as quickdoc])

(def github-repo "https://github.com/codethread/millhouse.spool")
(def git-branch "main")

(def spool-docs
  [{:source "spools/workflow/src/millhouse/spools/workflow.clj"
    :outfile "spools/workflow/workflow.api.md"}
   {:source "spools/chime/src/millhouse/spools/chime.clj"
    :outfile "spools/chime/chime.api.md"}
   {:source "spools/cron/src/millhouse/spools/cron.clj"
    :outfile "spools/cron/cron.api.md"}
   {:source "spools/workflow/src/millhouse/spools/executors/code.clj"
    :outfile "spools/workflow/code.api.md"}
   {:source "spools/workflow/src/millhouse/spools/executors/shell.clj"
    :outfile "spools/workflow/shell.api.md"}
   {:source "spools/kanban/src/millhouse/spools/kanban.clj"
    :outfile "spools/kanban/kanban.api.md"}
   {:source "spools/kanban/src/millhouse/spools/kanban/peering.clj"
    :outfile "spools/kanban/kanban.peering.api.md"}
   {:source ["spools/workflow/src/millhouse/spools/millstrand_workflows.clj"
             "spools/workflow/src/millhouse/spools/millstrand_workflows/bootstrap_kondo.clj"
             "spools/workflow/src/millhouse/spools/millstrand_workflows/bump_spool.clj"
             "spools/workflow/src/millhouse/spools/millstrand_workflows/bump_millstrand.clj"
             "spools/workflow/src/millhouse/spools/millstrand_workflows/consumer_tooling.clj"]
    :outfile "spools/workflow/millstrand-workflows.api.md"}])

(doseq [{:keys [source outfile]} spool-docs]
  (quickdoc/quickdoc
   {:source-paths (if (vector? source) source [source])
    :outfile outfile
    :github/repo github-repo
    :git/branch git-branch
    :var-pattern :wikilinks
    :toc false}))

(System/exit 0)
