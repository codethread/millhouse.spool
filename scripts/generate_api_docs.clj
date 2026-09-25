(require '[quickdoc.api :as quickdoc])

(def github-repo "https://github.com/codethread/millhouse.spool")
(def git-branch "main")

(def spool-docs
  [{:source ["spools/auto-review/src/millhouse/auto_review.clj"
             "spools/auto-review/src/millhouse/auto_review/glab.clj"
             "spools/auto-review/src/millhouse/auto_review/workspace.clj"
             "spools/auto-review/src/millhouse/auto_review/workflow.clj"]
    :outfile "spools/auto-review/auto-review.api.md"}
   {:source ["spools/auto-run/src/millhouse/auto_run.clj"
             "spools/auto-run/src/millhouse/auto_run_explain.clj"
             "spools/auto-run/src/millhouse/auto_run_reporting.clj"
             "spools/auto-run/src/millhouse/auto_run_recovery.clj"
             "spools/auto-run/src/millhouse/auto_run_worktree.clj"
             "spools/auto-run/src/millhouse/auto_run_land.clj"]
    :outfile "spools/auto-run/auto-run.api.md"}
   {:source ["spools/workflow/src/millhouse/workflow.clj"
             "spools/workflow/src/millhouse/workflow/validation.clj"]
    :outfile "spools/workflow/workflow.api.md"}
   {:source "spools/chime/src/millhouse/chime.clj"
    :outfile "spools/chime/chime.api.md"}
   {:source "spools/cron/src/millhouse/cron.clj"
    :outfile "spools/cron/cron.api.md"}
   {:source "spools/identity/src/millhouse/identity.clj"
    :outfile "spools/identity/identity.api.md"}
   {:source ["spools/land/src/millhouse/land.clj"
             "spools/land/src/millhouse/land/card_actions.clj"
             "spools/land/src/millhouse/land/merge_queue.clj"
             "spools/land/src/millhouse/land/support.clj"]
    :outfile "spools/land/land.api.md"}
   {:source "spools/workflow/src/millhouse/executors/code.clj"
    :outfile "spools/workflow/code.api.md"}
   {:source "spools/workflow/src/millhouse/executors/shell.clj"
    :outfile "spools/workflow/shell.api.md"}
   {:source "spools/kanban/src/millhouse/kanban.clj"
    :outfile "spools/kanban/kanban.api.md"}
   {:source "spools/workflow/src/millhouse/millstrand_workflows.clj"
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
