(ns ct.spools.devflow.guidance
  "Loader for the devflow authoring knowledge base.

  The knowledge itself lives as markdown under
  `resources/ct/spools/devflow/guidance/`: one cohesive document per artifact
  guide, the workspace overview, and the document templates. This namespace
  slurps those files and expands the placeholders that keep shared rules
  stated once:

  - `{{template:<file>}}`        — `templates/<file>`, fenced into the guide
  - `{{config-id:<PREFIX>}}`     — the configuration-identification paragraph,
                                   rendered for one document prefix
  - `{{id-editing}}`             — the shared ID-editing rule
  - `{{ownership-table:k1,k2}}`  — document-ownership rows for the named kinds

  Files are read on every call, so an edit under resources/ shows up on the
  next fetch without a weaver restart. Workflow steps advertise the guide for
  their artifact through the `devflow/guide` strand attribute; agents fetch a
  guide with `ct.spools.devflow/guidance` or `strand devflow guidance <key>`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Shared blocks, stated once and placed by placeholder

(def ^:private document-ownership
  "What each document kind owns, what it must not absorb, and how long it
  lives; `{{ownership-table:...}}` renders a guide's relevant rows. The root
  spec is the future-facing source of truth; archived feature folders explain
  why things changed, not what the current contract is."
  {:rfc          {:owns     "Idea framing, alternatives, tradeoffs, recommendation, decision outcome"
                  :not      "Implementation tracking or current feature state"
                  :lifetime "Active until implemented, then archived with the implementing feature"}
   :root-spec    {:owns     "Durable domain contracts, boundaries, rationale, non-goals"
                  :not      "Feature-local sequencing or task detail"
                  :lifetime "Permanent; evolves with the domain"}
   :proposal     {:owns     "Problem framing, goals, non-goals, scope, links to decisions — the intent agreed at sign-off"
                  :not      "Alternatives history (belongs in an RFC), implementation strategy (belongs in the plan), or how the work actually turned out (spec deltas, plan, and code carry that)"
                  :lifetime "Rewritable while under review; frozen at human sign-off and archived unchanged with the feature"}
   :spec-delta   {:owns     "Pending changes to durable specs staged by the feature"
                  :not      "Long-term duplicated spec content"
                  :lifetime "Merged into root specs when the feature ships, then archived"}
   :plan         {:owns     "Build strategy, phase boundaries, validation strategy, task context, developer notes"
                  :not      "Product problem framing or per-slice execution contracts"
                  :lifetime "Archived with the feature"}
   :tasks        {:owns     "Exact AFK/HITL slices, acceptance criteria, dependencies"
                  :not      "Durable design knowledge or ongoing notes"
                  :lifetime "Strands in the graph; closed as slices ship or are cut, with outcomes recorded in the plan"}
   :archive      {:owns     "Historical feature context after completion or abandonment, including implemented RFCs"
                  :not      "Active source of truth for current specs"
                  :lifetime "Permanent historical record"}
   :code         {:owns     "What exists and how it behaves"
                  :not      nil
                  :lifetime "Ground truth"}})

(def ^:private id-editing
  "The ID-editing rule shared by every guide's constraints; `{{id-editing}}`."
  "Preserve existing reference IDs when editing; append new IDs rather than renumbering unless the document is still a draft with no external references.")

(defn- config-identification
  "The configuration-identification header paragraph, rendered for a document
  prefix (PROP, RFC, SPEC, ...); `{{config-id:<PREFIX>}}`. Shared verbatim
  across all templates so the ID rules never drift between document kinds."
  [prefix]
  (str "**Configuration identification:** Document IDs must be ordered as document type, "
       "short name, sequential id, then optional version: `" prefix "-Dwr-001` for v1 and `"
       prefix "-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version "
       "supersedes an externally referenced document. Prefix every nested point ID with the "
       "full document ID, for example `" prefix "-Dwr-001.P1` or `" prefix "-Dwr-001@2.P1`, "
       "so references are globally grepable and do not clash across documents. If the next "
       "number or version is unclear, ask before creating the document."))

(defn- ownership-table
  "Render the `document-ownership` rows for `ks`, in the order given."
  [ks]
  (str "| Document | Owns | Must not absorb | Lifetime |\n"
       "|---|---|---|---|\n"
       (str/join "\n"
                 (map (fn [k]
                        (let [row (or (document-ownership k)
                                      (throw (ex-info "Unknown document-ownership kind"
                                                      {:kind k :kinds (vec (keys document-ownership))})))]
                          (str "| " (name k) " | " (:owns row) " | "
                               (or (get row :not) "—") " | " (:lifetime row) " |")))
                      ks))))

;; ---------------------------------------------------------------------------
;; Resource loading and placeholder expansion

(defn- slurp-resource [file]
  (let [path (str "ct/spools/devflow/guidance/" file)]
    (if-let [r (io/resource path)]
      (slurp r)
      (throw (ex-info "Missing devflow guidance resource" {:resource path})))))

(defn- fenced
  "Fence a template body for embedding in a guide, picking the fence language
  from the template's file extension."
  [file body]
  (str "```" (if (str/ends-with? file ".yml") "yaml" "markdown") "\n"
       body (when-not (str/ends-with? body "\n") "\n") "```"))

(declare expand)

(defn- expand-one [[placeholder key arg]]
  (case key
    "template" (fenced arg (expand (slurp-resource (str "templates/" arg))))
    "config-id" (config-identification arg)
    "id-editing" id-editing
    "ownership-table" (ownership-table (map keyword (str/split arg #",")))
    (throw (ex-info "Unknown devflow guidance placeholder"
                    {:placeholder placeholder}))))

(defn- expand [text]
  (str/replace text #"\{\{([a-z-]+)(?::([^{}]+))?\}\}" expand-one))

;; ---------------------------------------------------------------------------
;; The guide surface

(def ^:private guide-files
  "Every devflow authoring guide's resource file, by stable key. Workflow steps
  reference these keys through the `devflow/guide` attribute; the overview
  indexes them."
  {:proposal "proposal.md"
   :rfc "rfc.md"
   :spec "spec.md"
   :plan "plan.md"
   :tasks "tasks.md"
   :afk "afk.md"
   :decompose "decompose.md"
   :finish-archive "finish-archive.md"})

(defn guide
  "Return the markdown guide for `k`, failing loudly on an unknown key."
  [k]
  (if-let [file (guide-files k)]
    (expand (slurp-resource file))
    (throw (ex-info "Unknown devflow guide" {:guide k :guides (vec (keys guide-files))}))))

(defn overview
  "Return the devflow workspace orientation as markdown: layout, paths,
  invariants, the document-ID convention, document ownership, and an index of
  available guides."
  []
  (expand (slurp-resource "overview.md")))
