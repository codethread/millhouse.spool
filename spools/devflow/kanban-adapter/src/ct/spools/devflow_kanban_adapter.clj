(ns ct.spools.devflow-kanban-adapter
  "The kanban binding for devflow's pluggable seams.

  Devflow deliberately ships no coupling to any card system; the kanban spool
  deliberately ships no run tracking of its own. This root is the one place
  that knows both vocabularies, so consumers stop re-inventing the same glue:

  - `author-kanban-cards` — a card-authoring target for devflow's decompose
    defer that puts the breakdown on the kanban board as one epic card plus
    feature cards.
  - `decompose-kanban` — devflow's `decompose-open` template bound with that
    target beside the shipped strand-native default.
  - `repoint-decompose!` — a lifecycle-seed callable for workspaces that want
    the routed `:decompose` stage name to resolve to the kanban-bound variant.

  Requires the `codethread/devflow` and `millhouse.spools/kanban` roots; see
  this root's README for the consumer entry shape."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.devflow :as devflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.format.alpha :as format-alpha]
            [millhouse.spools.workflow :as workflow]))

(defn- titled [prefix]
  (fn [{:keys [feature]}]
    (str prefix feature)))

(defn- non-blank-string? [v]
  (and (string? v) (not (str/blank? v))))

(s/def ::feature non-blank-string?)
(s/def ::author-cards-params
  (s/and (s/keys :req-un [::feature]) :ct.spools.devflow.internal.definition/landed-input))
(s/def ::runtime some?)
(s/def ::repoint-input (s/keys :req-un [::runtime]))
(s/def ::seed-metadata-key (s/and keyword? #(not= :runtime %)))
(s/def ::seed-metadata (s/map-of ::seed-metadata-key any?))
(s/def ::repoint-seed-context
  (s/and
    (s/keys :req-un [::runtime])
    #(s/valid? ::seed-metadata (dissoc % :runtime))))
(s/def ::repointed #{:decompose})
(s/def ::repoint-result (s/keys :req-un [::repointed]))

(def ^:private repoint-input-keys #{:runtime})
(def ^:private repoint-seed-context-shape
  {:required-keys [:runtime]
   :metadata {:keys :keyword :values :any}})

(defn- sorted-keys [m]
  (vec (sort-by pr-str (keys m))))

(defn- require-valid!
  [spec value label]
  (if (s/valid? spec value)
    value
    (throw (ex-info label {:spec spec
                           :value value
                           :explain (s/explain-data spec value)}))))

(defn- require-seed-context!
  [context]
  (if (s/valid? ::repoint-seed-context context)
    context
    (let [received (if (map? context)
                     (sorted-keys context)
                     context)]
      (throw (ex-info
               (str "Invalid repoint-decompose-seed! context: allowed shape "
                    (pr-str repoint-seed-context-shape)
                    "; received " (pr-str received))
               {:spec ::repoint-seed-context
                :value context
                :allowed repoint-seed-context-shape
                :received received
                :explain (s/explain-data ::repoint-seed-context context)})))))

(workflow/defworkflow! author-kanban-cards
  "The kanban card-authoring target for devflow's decompose defer.

  Authors the decompose breakdown as kanban cards: one epic card grouping the
  set, one feature card per independently landable outcome, and landing-order
  constraints as depends-on edges. Card bodies carry the cold-card contract
  from `strand devflow guidance decompose`; board discipline comes from
  `strand prime kanban`. A card's strand id is the card id the review handoff
  expects. The epic is grouping-only — kanban refuses to claim one — so it
  stays out of the review set."
  {:entrypoints #{:call}
   :param-spec ::author-cards-params
   :defaults {}}
  (workflow/workflow
    (titled "Author kanban implementation cards for ")
    (workflow/step :draft-breakdown
                   (titled "Draft kanban breakdown for ")
                   :self
                   :attributes {"workflow/artifact" "implementation cards"
                                "devflow/guide" "decompose"}
                   (fn [{:keys [repository proposal-path merged-revision]}]
                     (format-alpha/prose
                     "
                     Read `strand devflow guidance decompose` and `strand prime kanban`.
                     Inspect approved proposal {path} in {repository} at the verified
                     revision {revision}, not a moving HEAD.

                     Draft the epic and independently landable feature cards, cold-card
                     bodies, stable local keys, and dependent-to-blocker edges. Persist
                     the draft at a durable reference before publishing anything. Complete
                     with devflow/breakdown-draft containing that reference and the exact
                     repository, proposal path and merged revision. This is the recovery
                     inventory; do not reconstruct it from memory after interruption.
                     "
                     {:repository repository :path proposal-path :revision merged-revision})))
    (workflow/step :publish-epic
                   (titled "Publish or recover the kanban epic for ")
                   :self
                   :depends-on [:draft-breakdown]
                   (format-alpha/prose
                     "
                     Read devflow/breakdown-draft on the closed draft-breakdown step:
                     inspect the run subgraph, then `strand show <step-id>`.
                     Read any existing devflow/epic-receipt on this step before acting.

                     Create one grouping epic with `strand kanban add <title> --type epic
                     --source <draft-reference>`. Record the returned epic id and draft
                     reference as devflow/epic-receipt on this step immediately, then
                     complete with that receipt. Reuse the recorded epic after checking
                     its source and type. If the add outcome is uncertain, inspect the
                     board for the exact draft source and reconcile the id before retrying;
                     do not create another epic just because completion was interrupted.
                     The kanban-batch pattern creates features, never an epic.
                     " {}))
    (workflow/step :publish-feature-graph
                   (titled "Publish or recover kanban feature cards and dependencies for ")
                   :self
                   :depends-on [:publish-epic]
                   (format-alpha/prose
                     "
                     Read the closed draft-breakdown and publish-epic receipts via
                     `strand subgraph <root-id>` and `strand show <step-id>`. Inspect
                     any devflow/card-publication receipt already stored on this step.

                     Publish the draft's feature cards and depends-on graph atomically
                     with `strand weave --pattern kanban-batch --input <json>`; inspect
                     `strand pattern explain kanban-batch` for its exact items contract.
                     Save the returned local-key to durable-id mapping immediately as
                     devflow/card-publication, with the draft reference and exact edges.
                     Keep the draft reference in each cold-card body for recovery.

                     The batch is atomic, not idempotent. If its result was lost, inspect
                     the board for that exact draft inventory and recover all ids before
                     retrying; stop on ambiguity rather than duplicate the batch. Reuse
                     verified published cards. Reconcile missing epic parent-of links
                     with `strand update <epic-id> --edge parent-of:<feature-id>`.
                     Verify every feature, body, dependency and epic membership against
                     the draft before completing with the full publication receipt.
                     " {}))
    (workflow/step :record-review-set
                   (titled "Record the exact kanban review set for ")
                   :self
                   :depends-on [:publish-feature-graph]
                   (format-alpha/prose
                     "
                     Read devflow/card-publication from the closed publish-feature-graph
                     step and devflow/epic-receipt from publish-epic using the run subgraph
                     and strand show. Verify those exact feature ids on the board and
                     their dependency edges; do not select cards by a title search.

                     Complete with devflow/review-set containing the exact nonempty
                     vector of feature refs (id and current title). Exclude the grouping
                     epic. This returns to the parent's handoff-card-review checkpoint;
                     its review input must be this recorded set. Later review corrections
                     update these same cards, not a fresh epic or duplicated publication.
                     " {}))))

(workflow/defworkflow! decompose-kanban
  "The decompose stage bound for kanban workspaces.

  Binds devflow's `decompose-open` template with the kanban authoring target
  beside the shipped strand-native default, so the defer's worker chooses per
  feature. Registered under its own name because devflow's module already owns
  `:decompose`; a workspace that wants the routed `:decompose` stage name to
  resolve here re-points it from a lifecycle seed with `repoint-decompose!`."
  {:entrypoints #{:continue :call}
   :param-spec :ct.spools.devflow.internal.definition/decompose-params
   :defaults {}}
  (workflow/bind-defers devflow/decompose-open
                        {:author-cards #{:author-card-strands :author-kanban-cards}}))

(defn repoint-decompose!
  "Re-point the routed `:decompose` stage name at `decompose-kanban`.

  This is the strict runtime operation used by the lifecycle adapter below. The
  re-point lives in the registry's direct layer, so it must be re-established on
  every weaver generation. `land-proposal`'s landed choice then routes into the
  kanban-bound variant.

  Accepts `{:runtime runtime}` satisfying `::repoint-input` and returns
  `{:repointed :decompose}` satisfying `::repoint-result`. The runtime is the
  lifecycle context's active Millstrand runtime. The input map is closed: any
  extra or missing key fails with the allowed and received key sets."
  [params]
  (let [params (if (map? params)
                 (let [received (set (keys params))]
                   (when-not (= repoint-input-keys received)
                     (throw (ex-info
                              (str "Invalid repoint-decompose! input: expected exact keys; "
                                   "allowed keys " (pr-str (vec (sort repoint-input-keys)))
                                   "; received keys " (pr-str (sorted-keys params)))
                              {:allowed (vec (sort repoint-input-keys))
                               :received (sorted-keys params)
                               :value params})))
                   params)
                 params)
        {:keys [runtime]} (require-valid! ::repoint-input params
                                           "Invalid repoint-decompose! input")]
    (current/with-runtime runtime
      (workflow/register-workflow! :decompose
                                   'ct.spools.devflow-kanban-adapter/decompose-kanban))
    (require-valid! ::repoint-result {:repointed :decompose}
                    "Invalid repoint-decompose! result")))

(defn repoint-decompose-seed!
  "Apply `repoint-decompose!` from a Millstrand lifecycle seed context.

  Lifecycle callables receive coordinator metadata in addition to `:runtime`;
  this adapter validates the `::repoint-seed-context` spec, whose metadata
  policy allows any additional keyword keys with arbitrary values, then
  projects the context to the strict public operation contract.
  The seed runner consumes the returned `{:repointed :decompose}` data result."
  [context]
  (let [{:keys [runtime]} (require-seed-context! context)]
    (repoint-decompose! {:runtime runtime})))
