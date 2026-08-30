
-----
# <a name="millhouse.spools.millstrand-workflows">millhouse.spools.millstrand-workflows</a>


Publisher-side workflows for reusable Millstrand and clj-kondo spool support.

  This namespace deliberately describes the publisher's obligations instead of
  trying to inspect a source tree. A publisher supplies the macro forms and
  their exported hook namespaces; the workflow turns those facts into an
  explicit, reviewable sequence of classpath, export, test, and documentation
  work.




## <a name="millhouse.spools.millstrand-workflows/publish-spool-kondo">`publish-spool-kondo`</a>




Publish clj-kondo support for a macro-owning spool root.

  Start the registered workflow with a complete publisher contract:

  ```clojure
  (require '[millhouse.spools.workflow :as workflow])

  (workflow/start! "publish-example" :publish-spool-kondo
    {:spool-root "spools/example-macros"
     :namespace "example.macros"
     :spool-key "example/macros"
     :macro-forms [{:macro "example.macros/defwidget"
                    :hook "hooks.example/defwidget"}]})
  ```

  The caller names the owning root, public namespace, spool key, and every
  macro-to-hook mapping. The workflow then walks the obligations in order:
  verify root ownership, publish resources on the root classpath, publish the
  explicit clj-kondo export and hooks, test the exported contract, and document
  the public surface. It does not discover macros automatically or perform
  filesystem edits itself; each step is an agent-facing instruction.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/spools/millstrand_workflows.clj#L111-L196">Source</a></sub></p>
