(ns millhouse.devflow.internal.definition
  "Internal shared workflow attributes, boundary specs and rendered gate prompts."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.format.alpha :as format-alpha]
            [millhouse.workflow :as workflow]))

(def artifact-guides
  "Maps each `workflow/artifact` value an authoring step advertises to the
  guidance key holding its authoring rules (see `guidance`). The brief has no
  guide; it is captured conversationally during intake."
  {"proposal.md" :proposal
   "specs/*.delta.md" :spec
   "<feature>.plan.md" :plan
   "task strands" :tasks
   "implementation cards" :decompose})

(def stages
  "Every stage name a devflow root may carry in its `devflow/stage` attribute.

  Stage is devflow's own vocabulary rather than an engine field, so this set is
  the enum the projections check a root against: `stage-attributes` is the only
  writer and `active-stage`/`run-history` are the readers. Names are routing-
  independent, so they need not match the `stage-workflows` keys."
  #{"intake" "proposal" "land-proposal" "decompose" "card-review" "spec-plan"
    "route-after-plan" "tasks" "afk" "implementation" "abort"})

(defn guided-artifact
  "Attributes for a step that authors a guided artifact: the artifact path, its
  guide key, and the instruction telling the driving agent to fetch that guide."
  [artifact]
  (let [guide (or (artifact-guides artifact)
                  (throw (ex-info "No guide registered for artifact"
                                  {:artifact artifact :artifacts (vec (keys artifact-guides))})))]
    {"workflow/artifact" artifact
     "devflow/guide" (name guide)
     "workflow/instruction" (str "Run `strand devflow guidance " (name guide) "` for the "
                                 "authoring procedure, constraints, template, and validation "
                                 "checklist before writing " artifact ".")}))

(defn titled
  "Render a feature-specific step or workflow title."
  ([prefix]
   (titled prefix ""))
  ([prefix suffix]
   (fn [{:keys [feature]}]
     (str prefix feature suffix))))

(defn param-value
  "Render one parameter as a step attribute."
  [k]
  (fn [params]
    (get params k)))

(defn stage-attributes
  "Root attributes every devflow stage workflow carries: its workflow family,
  the stage it was poured for, and the feature it runs against. Fails loudly on
  an unregistered stage name so a definition cannot mint a value the projections
  will later reject."
  [stage]
  (when-not (stages stage)
    (throw (ex-info "Unknown devflow stage name"
                    {:stage stage :stages (vec (sort stages))})))
  {"workflow/family" "devflow"
   "devflow/stage" stage
   "devflow/feature" (param-value :feature)})

(def ^:private loop-item-id-pattern
  "Loop item ids become workflow step ids, so they must be token-safe: no
  whitespace, slashes, colons, or leading punctuation."
  #"[A-Za-z0-9][A-Za-z0-9._-]*")

(defn- non-blank-string? [v]
  (and (string? v) (not (str/blank? v))))

;; Param contracts. Each stage names one whole-map spec the engine validates
;; before anything compiles or pours, so a bad param map fails at the
;; boundary with the spec's own explanation rather than part-way through a
;; stage. Task and card maps use keyword keys, matching loop expansion and
;; the workflow CLI's recursive JSON-to-keyword conversion.
(s/def ::feature non-blank-string?)
(s/def ::revision boolean?)
(s/def ::worktree-check #{"required" "already-in-worktree-ok"})
(s/def ::artifact non-blank-string?)
(s/def ::reason non-blank-string?)
(s/def ::delegate-harness non-blank-string?)
(s/def ::delegate-cwd non-blank-string?)
(s/def ::delegate-preamble non-blank-string?)
(s/def ::card-reviewer non-blank-string?)
(s/def ::card-set-reviewer non-blank-string?)
(s/def ::review-cwd non-blank-string?)

(defn- workflow-step-id? [v]
  (and (non-blank-string? v) (some? (re-matches loop-item-id-pattern v))))

(s/def ::id workflow-step-id?)
(s/def ::title non-blank-string?)
(s/def ::body non-blank-string?)
(s/def ::harness non-blank-string?)

(s/def ::afk-task
  (s/and (s/map-of keyword? any?)
         (s/keys :req-un [::id ::title] :opt-un [::body ::harness])))

(defn- distinct-item-ids? [items]
  (let [ids (map :id items)]
    (= (count ids) (count (distinct ids)))))

(s/def ::tasks
  (s/and (s/coll-of ::afk-task :kind vector? :min-count 1) distinct-item-ids?))

(s/def ::review-card
  (s/and (s/map-of keyword? any?) (s/keys :req-un [::id ::title])))

(s/def ::cards
  (s/and (s/coll-of ::review-card :kind vector? :min-count 1) distinct-item-ids?))

(defn- harnesses-resolve?
  "Every delegated task names a harness, or inherits the stage's default one."
  [{:keys [tasks delegate-harness]}]
  (every? #(non-blank-string? (or (:harness %) delegate-harness)) tasks))

(s/def ::intake-params
  (s/keys :req-un [::feature]
          :opt-un [::worktree-check ::revision ::card-reviewer
                   ::card-set-reviewer ::review-cwd]))
(s/def ::agent-review-params (s/keys :req-un [::feature ::artifact]))
(s/def ::proposal-params (s/keys :req-un [::feature] :opt-un [::revision]))
(s/def ::land-proposal-params
  (s/keys :req-un [::feature ::card-reviewer ::card-set-reviewer]
          :opt-un [::review-cwd]))
(s/def ::decompose-params
  (s/keys :req-un [::feature ::card-reviewer ::card-set-reviewer]
          :opt-un [::review-cwd]))
(s/def ::card-set-input (s/keys :req-un [::cards]))
(s/def ::review-cards-params
  (s/keys :req-un [::feature ::card-reviewer ::card-set-reviewer ::cards]
          :opt-un [::review-cwd ::revision]))
(s/def ::spec-plan-params (s/keys :req-un [::feature] :opt-un [::revision]))
(s/def ::route-after-plan-params (s/keys :req-un [::feature]))
(s/def ::tasks-params (s/keys :req-un [::feature] :opt-un [::revision]))
(s/def ::run-afk-loop-params
  (s/keys :req-un [::feature]
          :opt-un [::tasks ::delegate-harness ::delegate-cwd ::delegate-preamble]))
(s/def ::run-afk-manual-params (s/keys :req-un [::feature]))
(s/def ::run-afk-delegated-params
  (s/and (s/keys :req-un [::feature ::tasks]
                 :opt-un [::delegate-harness ::delegate-cwd ::delegate-preamble ::revision])
         harnesses-resolve?))
(s/def ::direct-implementation-params (s/keys :req-un [::feature] :opt-un [::revision]))
(s/def ::abort-params (s/keys :req-un [::feature ::reason]))
(s/def ::author-strands-params (s/keys :req-un [::feature]))

;; Choice input contracts: the whole map `choose!` must accept, resolved live
;; at the checkpoint rather than baked in when the stage poured.
(s/def ::abort-reason-input (s/keys :req-un [::reason]))
(s/def ::delegation-input
  (s/and (s/keys :req-un [::tasks]
                 :opt-un [::delegate-harness ::delegate-cwd ::delegate-preamble])
         harnesses-resolve?))
(s/def ::worktree (s/and non-blank-string? #(.isAbsolute (java.io.File. ^String %))))
(s/def ::branch non-blank-string?)
(s/def ::repository non-blank-string?)
(s/def ::worktree-input (s/keys :req-un [::repository ::worktree ::branch]))
(s/def ::mainline non-blank-string?)
(s/def ::merged-revision non-blank-string?)
(s/def ::proposal-path non-blank-string?)
(s/def ::merge-evidence non-blank-string?)
(s/def ::landed-input
  (s/keys :req-un [::repository ::mainline ::merged-revision
                   ::proposal-path ::merge-evidence]))
(s/def ::author-cards-params
  (s/and ::author-strands-params ::landed-input))

(defn- afk-task-prompt [feature task delegate-preamble]
  (format-alpha/prose
    "
    {preamble}

    Devflow AFK task for {feature}: {title}

    {body}
    "
    {:preamble (or delegate-preamble "") :feature feature
     :title (:title task)
     :body (or (:body task) (:title task))}))

(defn afk-task-gate
  "The per-task subagent gate the delegated AFK stage expands one of per task.

  Every value renders from resolved params, which is what lets the stage be a
  static definition: nothing here is decided when the definition is written.
  `harness/cwd` is always declared and renders nil when no `:delegate-cwd`
  was supplied, which the agent executor reads exactly as an absent cwd."
  []
  (workflow/gate :task
                 (fn [{:keys [feature item]}]
                   (str "Delegate AFK task " (:id item) " for " feature))
                 :agent
                 :loop {:each :tasks :chain true}
                 :attributes {"devflow/task" (fn [{:keys [item]}] (:id item))
                              "harness/alias" (fn [{:keys [item delegate-harness]}]
                                                (or (:harness item) delegate-harness))
                              "harness/cwd" (param-value :delegate-cwd)
                              "harness/prompt" (fn [{:keys [feature item delegate-preamble]}]
                                                 (afk-task-prompt feature item delegate-preamble))}))

(defn card-review-prompt
  "Render the focused review prompt for one card."
  [{:keys [feature item]}]
  (format-alpha/prose
    "
    Review one implementation card for {feature} as a focused, read-only
    reviewer. Use the workspace's card system to inspect {id} ({title}) and
    read the merged, approved proposal it implements.

    Judge only this card's cold-work contract: current-state evidence, target
    outcome, constraints, proposal traceability, explicit done-when, validation
    gates, landing discipline and whether direct dependencies let it land
    independently. Do not redesign the set or repeat set-wide coverage analysis;
    a separate reviewer owns relationships across cards. Do not edit cards.

    Return `VERDICT: pass` or `VERDICT: revise`, followed by concrete findings
    ordered by severity. Say plainly when the card passes.
    "
    {:feature feature :id (:id item) :title (:title item)}))

(defn card-set-review-prompt
  "Render the set-level review prompt after every focused card review fans in."
  [{:keys [feature cards]}]
  (format-alpha/prose
    "
    Review the implementation-card decomposition for {feature} as the set-level,
    read-only reviewer. Focused reviewers already reviewed each card's cold-work
    contract; do not repeat that fine-grained work. Inspect these cards:

    {cards}

    Review only the connections and whole-set shape: complete proposal-goal
    coverage, gaps and overlaps, outcome-oriented slicing, independently landable
    increments, dependency-edge direction and necessity, integration seams and
    open decisions that cold workers might otherwise decide inconsistently.
    Do not edit cards.

    Return `VERDICT: pass` or `VERDICT: revise`, followed by concrete set-level
    findings ordered by severity. Say plainly when the decomposition is cohesive.
    "
    {:feature feature
     :cards (str/join "\n" (map #(str "- " (:id %) ": " (:title %))
                                cards))}))

(def abort-reason-input
  "Declared choice input for every abort choice: a required `:reason` recorded on
  the abort step and surfaced with the choice (workflow.md §5). `choose!` fails
  loudly before any mutation when it is omitted."
  {:spec ::abort-reason-input
   :doc "Why the feature is being aborted; recorded on the abort step."})
