(ns millhouse.spools.millstrand-workflows
  "Publisher-side workflows for reusable Millstrand and clj-kondo spool support.

  This namespace deliberately describes the publisher's obligations instead of
  trying to inspect a source tree. A publisher supplies the macro forms and
  their exported hook namespaces; the workflow turns those facts into an
  explicit, reviewable sequence of classpath, export, test, and documentation
  work."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.spools.millstrand-workflows.bootstrap-kondo :as bootstrap]
            [millhouse.spools.millstrand-workflows.bump-millstrand :as millstrand]
            [millhouse.spools.millstrand-workflows.bump-spool :as bump]
            [millhouse.spools.millstrand-workflows.consumer-tooling :as tooling]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.format.alpha :as format-alpha]))

(defn- non-blank-string?
  "Return true when `value` is a non-blank string."
  [value]
  (and (string? value) (not (str/blank? value))))

(s/def ::spool-root non-blank-string?)
(s/def ::namespace (s/and non-blank-string? #(re-matches #"[A-Za-z][A-Za-z0-9_.-]*" %)))
(s/def ::spool-key (s/and non-blank-string? #(re-matches #"[^/\s]+/[^/\s]+" %)))
(s/def ::macro (s/and non-blank-string? #(re-matches #"[^/\s]+/[^/\s]+" %)))
(s/def ::hook (s/and non-blank-string? #(re-matches #"[^/\s]+/[^/\s]+" %)))
(s/def ::macro-form (s/keys :req-un [::macro ::hook]))
(s/def ::macro-forms
  (s/and (s/coll-of ::macro-form :kind vector? :min-count 1)
         #(apply distinct? (map :macro %))))
(s/def ::publish-params
  (s/keys :req-un [::spool-root ::namespace ::spool-key ::macro-forms]))

(defn- macro-summary
  "Render the explicitly supplied macro-to-hook mappings for instructions."
  [{:keys [macro-forms]}]
  (str/join ", " (map (fn [{:keys [macro hook]}]
                        (str "`" macro "` -> `" hook "`"))
                      macro-forms)))

(defn- root-instruction
  [{:keys [spool-root namespace spool-key]}]
  (format-alpha/reflow
   (format
    "|Inspect `%s` as the owning root for namespace `%s` and spool key `%s`.
     |Confirm that one producer source owns every macro listed in the parameters.
     |Record the source files and public macro forms that this publication is
     |responsible for. Do not infer ownership by scanning consumer remaps or
     |generated imports."
    spool-root namespace spool-key)))

(defn- classpath-instruction
  [{:keys [spool-root]}]
  (format-alpha/reflow
   (format
    "|In `%s/deps.edn`, keep both `src` and `resources` on the root's `:paths`.
     |Verify from a clean consumer classpath that the exported resource path is
     |reachable through `io/resource`. A source-only path is not publication."
    spool-root)))

(defn- export-instruction
  [{:keys [spool-root] :as params}]
  (format-alpha/reflow
   (format
    "|Create or update the export under `%s/resources/clj-kondo.exports/`.
     |Its config must name the macro analysis entries for %s. Keep the mapping
     |complete and explicit: every macro form gets its corresponding hook, and
     |the config is reviewed as a public compatibility surface. There is no
     |automatic macro discovery step."
    spool-root (macro-summary params))))

(defn- hook-instruction
  [{:keys [macro-forms]}]
  (format-alpha/reflow
   (format
    "|Implement and export the hook functions for %s. Each hook should rewrite
     |only the syntax shape of its named macro (for example, a def-like form or
     |a let-shaped binding) so clj-kondo can analyze the body. Add a hook when a
     |macro shape changes; do not make the hook scan or guess other macros."
    (macro-summary {:macro-forms macro-forms}))))

(defn- test-instruction
  [{:keys [spool-root namespace]}]
  (format-alpha/reflow
   (format
    "|From the repository root, run the focused tests for `%s`, then lint the
     |exported source from a clean classpath. Exercise `%s` through each public
     |macro shape and assert that the producer config and hook resources resolve
     |directly, without a generated self-import or overlapping consumer remap.
     |Review external imports and inspect source/export drift before repeating
     |the relevant quality checks."
    spool-root namespace)))

(defn- docs-instruction
  [{:keys [spool-root namespace macro-forms]}]
  (format-alpha/reflow
   (format
    "|Update `%s/README.md` and its cookbook/API companion with the namespace
     |`%s`, spool key, activation recipe, resource path, and the explicit macro
     |mapping %s. Document the test command and the maintenance rule: a changed
     |macro shape requires a reviewed export hook, tests, and documentation."
    spool-root namespace (macro-summary {:macro-forms macro-forms}))))

(defn- final-status-instruction
  [{:keys [spool-root]}]
  (format-alpha/reflow
   (format
    "|After the reviewed publication is committed, run `git diff --check` and
     |`git status --short` from the selected checkout. Confirm that no tracked
     |`.clj-kondo/.cache` file exists and that the final status is empty. The
     |producer export under `%s/resources` is the only source of its mapping."
    spool-root)))

(workflow/defworkflow! publish-spool-kondo
  "Publish clj-kondo support for a macro-owning spool root.

  Start the registered workflow with a complete publisher contract:

  ```clojure
  (require '[millhouse.spools.workflow :as workflow])

  (workflow/start! \"publish-example\" :publish-spool-kondo
    {:spool-root \"spools/example-macros\"
     :namespace \"example.macros\"
     :spool-key \"example/macros\"
     :macro-forms [{:macro \"example.macros/defwidget\"
                    :hook \"hooks.example/defwidget\"}]})
  ```

  The caller names the owning root, public namespace, spool key, and every
  macro-to-hook mapping. The workflow then walks the obligations in order:
  verify root ownership, publish resources on the root classpath, publish the
  explicit clj-kondo export and hooks, test the exported contract, and document
  the public surface. It does not discover macros automatically or perform
  filesystem edits itself; each step is an agent-facing instruction."
  {:entrypoints #{:start}
   :param-spec ::publish-params
   :defaults {}
   :example {:spool-root "spools/example-macros"
             :namespace "example.macros"
             :spool-key "example/macros"
             :macro-forms [{:macro "example.macros/defwidget"
                            :hook "hooks.example/defwidget"}]}
   :param-docs {:spool-root "Owning spool root containing source and resources."
                :namespace "Public namespace that owns the macro forms."
                :spool-key "Approved spool coordinate for the owning root."
                :macro-forms
                "Vector of explicit macro and exported-hook mappings; no discovery is implied."}}
  (workflow/workflow
   (fn [{:keys [namespace]}]
     (str "Publish clj-kondo support for " namespace))
   {:attributes {"workflow/family" "millstrand-workflows"
                 "millstrand-workflows/obligations"
                 ["one-producer-source" "root-classpath" "kondo-export"
                  "kondo-hooks" "import-review" "tests" "docs" "clean-status"]}}
   (workflow/step :inspect-spool-root
                  "Inspect the macro-owning spool root"
                  :self
                  root-instruction)
   (workflow/step :publish-root-classpath
                  "Publish resources on the spool root classpath"
                  :self
                  :depends-on [:inspect-spool-root]
                  classpath-instruction)
   (workflow/step :publish-kondo-export
                  "Publish the explicit clj-kondo export"
                  :self
                  :depends-on [:publish-root-classpath]
                  export-instruction)
   (workflow/step :publish-kondo-hooks
                  "Publish hooks for each macro shape"
                  :self
                  :depends-on [:publish-kondo-export]
                  hook-instruction)
   (workflow/step :review-import-boundary
                  "Review external imports and consumer remaps"
                  :self
                  :depends-on [:publish-kondo-hooks]
                  (format-alpha/reflow
                   "|Use the producer resource directory as the one source for
                     |this export. Review external dependency imports separately,
                     |inspect import drift, and reject any consumer config remap
                     |that overlaps the producer mapping. Remove generated
                     |self-imports and any tracked `.clj-kondo/.cache` file."))
   (workflow/step :test-kondo-export
                  "Test the exported clj-kondo contract"
                  :self
                  :depends-on [:review-import-boundary]
                  test-instruction)
   (workflow/step :document-kondo-export
                  "Document the exported macro contract"
                  :self
                  :depends-on [:test-kondo-export]
                  docs-instruction)
   (workflow/step :verify-clean-status
                  "Verify clean final Git status"
                  :self
                  :depends-on [:document-kondo-export]
                  final-status-instruction)))

(workflow/use-workflow!
 bootstrap/bootstrap-kondo
 bootstrap/bootstrap-kondo-greenfield
 bootstrap/bootstrap-kondo-brownfield
 bump/bump-spool
 millstrand/bump-millstrand
 millstrand/bump-millstrand-local
 millstrand/bump-millstrand-local-validate
 millstrand/bump-millstrand-pinned
 tooling/configure-consumer-tooling
 tooling/configure-consumer-tooling-app
 tooling/configure-consumer-tooling-spool
 tooling/configure-consumer-tooling-clojure-app)
