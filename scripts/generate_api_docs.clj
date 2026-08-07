(require '[quickdoc.api :as quickdoc])

(def github-repo "https://github.com/codethread/millhouse.spool")
(def git-branch "main")

(def spool-docs
  [{:source "spools/chime/src/millstrand/spools/chime.clj"
    :outfile "spools/chime/chime.api.md"}
   {:source "spools/cron/src/millstrand/spools/cron.clj"
    :outfile "spools/cron/cron.api.md"}
   {:source "spools/code-executor/src/millstrand/spools/executors/code.clj"
    :outfile "spools/code-executor/code.api.md"}
   {:source "spools/shell-executor/src/millstrand/spools/executors/shell.clj"
    :outfile "spools/shell-executor/shell.api.md"}])

(doseq [{:keys [source outfile]} spool-docs]
  (quickdoc/quickdoc
   {:source-paths [source]
    :outfile outfile
    :github/repo github-repo
    :git/branch git-branch
    :var-pattern :wikilinks
    :toc false}))

(System/exit 0)
