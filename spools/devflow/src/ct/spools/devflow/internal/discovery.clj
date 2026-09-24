(ns ct.spools.devflow.internal.discovery
  "Internal CLI metadata for the Devflow guidance operation."
  (:require [millstrand.api.format.alpha :as format-alpha]))

(def devflow-arg-spec
  "Declared command surface for the `devflow` op."
  {:op "devflow"
   :doc "Devflow's static authoring knowledge, served to CLI workers."
   :subcommands
   {"guidance"
    {:doc (str "Show the devflow workspace overview, or one artifact's full "
               "authoring guide.")
     :hook-class :read
     :deadline-class :standard
     :positionals [{:name :guide
                    :type :string
                    :doc (format-alpha/prose
                      "
                      Guide key, as advertised by a step's devflow/guide attribute (e.g. proposal).
                      Omit for the workspace overview, which indexes every key.
                      " {})}]
     :annotations
     {:use-when [(str "A ready step carries a devflow/guide attribute and you "
                      "are about to author its artifact.")
                 (format-alpha/prose
                   "
                   Working outside a run: rfc and finish-archive have no workflow step, and the
                   overview orients any devflow workspace work.
                   " {})]
      :notes [(format-alpha/prose
        "
        The payload is resolved live from the loaded spool on every call, never from
        run state, so it is always the current guide. The Clojure equivalent is
        (ct.spools.devflow/guidance <key>).
        " {})]}}}})

(def devflow-returns
  {:subcommands
   {"guidance" {:type :map
                :required {:operation :string
                           ;; One markdown document, loaded from the spool's
                           ;; guidance resources by ct.spools.devflow.guidance.
                           :guidance :string}
                :optional {:guide :string}}}})

(def devflow-meta
  "Cross-verb narrative for `devflow`, projected by the `about`/`prime`
  meta-verbs."
  {:about (format-alpha/reflow
           "|devflow ships the feature-delivery lifecycle as ordinary Millstrand
            |workflow definitions, driven through the generic workflow op; this
            |op adds no run verbs. guidance is its one read: the static authoring
            |knowledge behind the lifecycle. With no argument it returns the
            |workspace overview — layout, paths, invariants, the document-ID
            |convention, document ownership, and an index of every guide key. With
            |a key it returns that artifact's authoring guide as one markdown
            |document: purpose, prerequisites, knowledge, procedures, constraints,
            |validation checklist, and templates. Artifact-authoring steps advertise
            |their key in the devflow/guide strand attribute, and the payload resolves
            |live from the loaded spool rather than from anything recorded on the run.")
   :prime (format-alpha/reflow
           "|Run `strand devflow guidance` for the workspace overview and the index
            |of guide keys, then `strand devflow guidance <key>` (e.g. proposal)
            |before authoring that artifact. When driving a workflow run, the ready
            |step's devflow/guide attribute names the key to fetch. rfc and
            |finish-archive belong to no step: fetch rfc when intake or proposal work
            |exposes real uncertainty, and finish-archive after squash-run! to close
            |out a feature.")})
