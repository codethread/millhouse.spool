(ns millhouse.spools.identity
  "Logical native-session identities and optional run provenance."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [millstrand.api.authoring.alpha :as authoring]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.peers.alpha :as peers]
            [millstrand.api.registry.alpha :as registry]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.io RandomAccessFile]
           [java.nio.charset StandardCharsets]
           [java.security SecureRandom]
           [java.util Base64 UUID]))

(def ^:private adjectives
  ["amber" "brave" "bright" "calm" "clear" "cool" "coral" "crisp"
   "eager" "fair" "gentle" "golden" "green" "happy" "kind" "lively"
   "lucid" "merry" "nimble" "quiet" "rapid" "ready" "silver" "smart"
   "steady" "sunny" "swift" "tidy" "vivid" "warm" "wise" "young"])
(def ^:private nouns
  ["badger" "bear" "beaver" "bison" "crane" "dolphin" "eagle" "falcon"
   "finch" "fox" "gecko" "heron" "ibis" "koala" "lemur" "lynx"
   "marten" "moose" "otter" "owl" "panda" "puma" "raven" "seal"
   "shark" "stoat" "swan" "tiger" "whale" "wolf" "wombat" "yak"])
(def ^:private ^SecureRandom rng (SecureRandom.))
(def ^:private identity-monitor (Object.))

(s/def ::runtime map?)
(s/def ::harness (s/and string? (complement str/blank?)))
(s/def ::native-session-id (s/and string? (complement str/blank?)))
(s/def ::model (s/and string? (complement str/blank?)))
(s/def ::thinking-level (s/and string? (complement str/blank?)))
(s/def ::run-id (s/and string? (complement str/blank?)))
(s/def ::identity (s/and string? (complement str/blank?)))
(s/def ::parent-identity (s/and string? (complement str/blank?)))
(s/def ::reservation-id (s/and string? (complement str/blank?)))
(s/def ::expected-identity (s/and string? (complement str/blank?)))
(s/def ::attribute qualified-keyword?)
(s/def ::relation (s/and string? (complement str/blank?)))
(s/def ::attribution-contribution
  (s/and (s/keys :req-un [::attribute ::relation])
         #(= #{:attribute :relation} (set (keys %)))))
(s/def ::contribution-key keyword?)
(s/def ::source-ids
  (s/coll-of (s/and string? (complement str/blank?)) :kind coll?))
(s/def ::lifecycle-context
  (s/and map? #(s/valid? ::runtime (:runtime %))))
(s/def ::attribution-handle #{:identity/attribution})

(def ^:private startup-request-keys
  #{:harness :native-session-id :model :thinking-level :run-id :identity
    :parent-identity :reservation-id})
(def ^:private attach-request-keys
  #{:harness :native-session-id :run-id :identity :parent-identity :reservation-id})
(def ^:private reserve-request-keys #{:harness :model :thinking-level})
(def ^:private bind-request-keys
  #{:harness :native-session-id :model :thinking-level :run-id :expected-identity})

(s/def ::startup-request
  (s/and (s/keys :req-un [::harness ::native-session-id]
                 :opt-un [::model ::thinking-level ::run-id ::identity
                          ::parent-identity ::reservation-id])
         #(every? startup-request-keys (keys %))))
(s/def ::attach-request
  (s/and (s/keys :req-un [::harness ::native-session-id ::reservation-id]
                 :opt-un [::run-id ::identity ::parent-identity])
         #(every? attach-request-keys (keys %))))
(s/def ::reserve-request
  (s/and (s/keys :req-un [::harness]
                 :opt-un [::model ::thinking-level])
         #(every? reserve-request-keys (keys %))))
(s/def ::bind-request
  (s/and (s/keys :req-un [::harness ::native-session-id]
                 :opt-un [::model ::thinking-level ::run-id ::expected-identity])
         #(every? bind-request-keys (keys %))))

(defn identity?
  "Return true when `strand` is an identity record."
  [strand]
  (= "true" (attr-get strand :identity/session)))

(defn- by-native-session [runtime harness native-session-id]
  (filterv #(and (identity? %)
                 (= harness (attr-get % :identity/harness))
                 (= native-session-id (attr-get % :identity/native-session-id)))
           (weaver/list runtime)))

(defn- by-friendly-id [runtime friendly-id]
  (filterv #(and (identity? %)
                 (= friendly-id (attr-get % :identity/id)))
           (weaver/list runtime)))

(defn- by-reservation-id [runtime reservation-id]
  (filterv #(and (identity? %)
                 (= reservation-id (attr-get % :identity/reservation-id)))
           (weaver/list runtime)))

(defn current
  "Resolve an existing identity by friendly ID, failing when absent or ambiguous."
  [runtime friendly-id]
  (let [matches (by-friendly-id runtime friendly-id)]
    (when-not (= 1 (count matches))
      (fail! "Identity does not resolve uniquely"
             {:identity friendly-id :matches (mapv :id matches)}))
    (first matches)))

(def attribution-kind
  "Owner-partitioned registry kind for explicit spool attribution roles."
  :millhouse.spools.identity/attributions)

(def ^:private canonical-attribution
  {:key :identity/by-identity
   :attribute :identity/by-identity
   :relation "attributed"})

(authoring/register-registry-kind! attribution-kind ::attribution-contribution)

(defn validate-attribution-contributions!
  "Validate the effective custom attribution contribution set.

  Each attribute and relation is owned by exactly one contribution. The
  canonical `:identity/by-identity`/`attributed` pair is reserved. This function
  is public because the runtime resolves it as the registry candidate validator."
  [{:keys [entries] :as context}]
  (let [contributions (mapv (fn [[key contribution]]
                              (assoc contribution :key key))
                            entries)
        all (conj contributions canonical-attribution)]
    (doseq [field [:attribute :relation]
            :let [duplicates (->> all
                                  (group-by field)
                                  (keep (fn [[value matches]]
                                          (when (< 1 (count matches)) value)))
                                  (sort-by str)
                                  vec)]
            :when (seq duplicates)]
      (fail! "Attribution contributions must own unique attributes and relations"
             {:field field :duplicates duplicates :contributions all})))
  context)

(defn- new-attribution-kinds []
  (doto (registry/registry)
    (registry/declare-kind!
     {:id attribution-kind
      :entry-spec ::attribution-contribution
      :binding-moment :identity/reconcile
      :candidate-validator
      'millhouse.spools.identity/validate-attribution-contributions!})))

(defn- attribution-kinds [rt]
  (runtime/spool-state rt ::attribution-kinds new-attribution-kinds))

(runtime/collect-kind!
 ::attribution-kinds
 {:id attribution-kind
  :entry-spec ::attribution-contribution
  :binding-moment :identity/reconcile
  :candidate-validator
  'millhouse.spools.identity/validate-attribution-contributions!})

(defn contribute-attribution!
  "Publish one explicit spool-owned attribution role during module collection.

  `key` identifies the contribution. `attribute` is the durable raw friendly-ID
  attribute on source records, and `relation` is the role-specific edge name.
  Both are exclusive to the contribution. Outside module collection the call is
  passive, matching `runtime/collect-entry!`.

  ```clojure
  (identity/contribute-attribution!
    :kanban/reporter :kanban/reporter \"reported\")
  ```"
  [key attribute relation]
  (require-valid! ::contribution-key key
                  "Attribution contribution key must be a keyword")
  (runtime/collect-entry!
   attribution-kind key
   (require-valid! ::attribution-contribution
                   {:attribute attribute :relation relation}
                   "Attribution contribution is invalid")))

(defn attribution-contributions
  "Return the canonical and effective explicit attribution contributions.

  The canonical entry is always first; custom entries follow in deterministic
  key order. No attribute namespace is scanned or inferred."
  [rt]
  (let [custom (registry/effective (attribution-kinds rt) attribution-kind)
        contributions (into [canonical-attribution]
                            (map (fn [[key contribution]]
                                   (assoc contribution :key key)))
                            custom)]
    (validate-attribution-contributions!
     {:entries (into {} (map (juxt :key #(select-keys % [:attribute :relation])))
                     (rest contributions))})
    contributions))

(defn- projection-status [identity-index raw]
  (if (nil? raw)
    {:status :absent :identity-strand-ids []}
    (if-not (s/valid? ::identity raw)
      {:status :malformed :identity-strand-ids []}
      (let [matches (sort (map :id (get identity-index raw)))]
        {:status (case (count matches)
                   0 :unresolved
                   1 :resolved
                   :ambiguous)
         :identity-strand-ids (vec matches)}))))

(defn inspect-attributions
  "Project durable identity attribution evidence and its current graph links.

  The zero-filter form scans every source carrying a configured attribute plus
  any source with a managed edge. `source-ids`, when supplied, bounds that
  projection and every id must exist. Each result always contains
  `:source-id`, `:contribution`, `:attribute`, `:relation`, raw `:identity`,
  `:status`, exact `:identity-strand-ids`, and current
  `:linked-identity-strand-ids`. Status is `:resolved`, `:unresolved`,
  `:ambiguous`, `:malformed`, or `:absent`."
  ([rt] (inspect-attributions rt nil))
  ([rt source-ids]
   (when source-ids
     (require-valid! ::source-ids source-ids
                     "Attribution source ids must be non-blank strings"))
   (let [strands (weaver/list rt)
         strands-by-id (into {} (map (juxt :id clojure.core/identity)) strands)
         requested (when source-ids (set source-ids))
         _ (doseq [source-id requested]
             (when-not (contains? strands-by-id source-id)
               (fail! "Attribution source not found" {:source-id source-id})))
         identity-index (group-by #(attr-get % :identity/id)
                                  (filter identity? strands))
         all-ids (mapv :id strands)]
     (->> (attribution-contributions rt)
          (mapcat
           (fn [{:keys [key attribute relation]}]
             (let [edges (if (seq all-ids)
                           (graph/incoming-edges rt all-ids relation)
                           [])
                   linked-by-source (group-by :to_strand_id edges)
                   evidence-ids (into #{}
                                      (keep (fn [strand]
                                              (when (some? (attr-get strand attribute))
                                                (:id strand))))
                                      strands)
                   candidate-ids (into evidence-ids (keys linked-by-source))]
               (for [source-id (sort candidate-ids)
                     :when (or (nil? requested) (contains? requested source-id))
                     :let [source (get strands-by-id source-id)
                           raw (attr-get source attribute)
                           linked (->> (get linked-by-source source-id)
                                       (map :from_strand_id)
                                       sort
                                       vec)]]
                 (merge {:source-id source-id
                         :contribution key
                         :attribute attribute
                         :relation relation
                         :identity raw
                         :linked-identity-strand-ids linked}
                        (projection-status identity-index raw))))))
          (sort-by (juxt :source-id (comp str :contribution)))
          vec))))

(defn- sync-projection! [rt projection]
  (let [{:keys [source-id relation status identity-strand-ids
                linked-identity-strand-ids]} projection
        expected (when (= :resolved status) (first identity-strand-ids))
        stale (remove #{expected} linked-identity-strand-ids)
        missing? (and expected (not-any? #{expected} linked-identity-strand-ids))
        linked-refs (into {}
                          (map-indexed (fn [index id]
                                         [(keyword (str "linked-" index)) id]))
                          stale)
        expected-ref (when missing? :resolved-identity)
        refs (cond-> (assoc linked-refs :source source-id)
               expected-ref (assoc expected-ref expected))
        edges (into (mapv (fn [[ref _]]
                            {:op :remove :from ref :to :source :type relation})
                          linked-refs)
                    (when expected-ref
                      [{:op :upsert :from expected-ref :to :source :type relation}]))]
    (when (seq edges)
      (batch/apply! rt {:refs refs :strands [] :edges edges :burn []}))
    (count edges)))

(defn reconcile-attributions!
  "Converge attribution edges from durable source attributes.

  Exact unique local identity matches create `identity -> source` edges using
  each contribution's relation. Unknown and ambiguous names are nonfatal and
  leave no relation; stale links are removed. Malformed evidence fails before
  any write. Repeated reconciliation with a converged graph performs no write.
  The optional `source-ids` collection bounds an explicit repair. Returns exactly
  `:scanned`, `:resolved`, `:unresolved`, `:ambiguous`, `:absent`, and `:writes`;
  `:writes` counts edge mutations submitted by this call."
  ([rt] (reconcile-attributions! rt nil))
  ([rt source-ids]
   (let [projections (inspect-attributions rt source-ids)
         malformed (filterv #(= :malformed (:status %)) projections)]
     (when (seq malformed)
       (fail! "Malformed identity attribution evidence"
              {:attributions malformed}))
     (let [writes (reduce + (map #(sync-projection! rt %) projections))
           statuses (frequencies (map :status projections))]
       {:scanned (count projections)
        :resolved (get statuses :resolved 0)
        :unresolved (get statuses :unresolved 0)
        :ambiguous (get statuses :ambiguous 0)
        :absent (get statuses :absent 0)
        :writes writes}))))

(def ^:private attribution-event-types
  #{:strand/added :strand/updated :batch/applied})

(defn on-attribution-event
  "Post-commit event handler that accelerates durable attribution convergence."
  [_event]
  (reconcile-attributions! (current/runtime)))

(defn open-attribution-engine!
  "Register the attribution handler and reconcile durable sources at activation."
  [{:keys [runtime] :as context}]
  (require-valid! ::lifecycle-context context
                  "Invalid identity attribution lifecycle context")
  (events/register-handler!
   runtime :identity/attribution attribution-event-types
   'millhouse.spools.identity/on-attribution-event
   {:spool "identity"})
  (try
    (reconcile-attributions! runtime)
    :identity/attribution
    (catch Throwable cause
      (events/unregister-handler! runtime :identity/attribution)
      (throw (ex-info "Identity attribution activation failed and was reverted"
                      {:effect/id (:effect/id context)} cause)))))

(defn close-attribution-engine!
  "Unregister the attribution handler. Durable evidence and edges remain."
  [{:keys [runtime resource] :as context}]
  (require-valid! ::lifecycle-context context
                  "Invalid identity attribution lifecycle context")
  (require-valid! ::attribution-handle resource
                  "Invalid identity attribution resource")
  (events/unregister-handler! runtime :identity/attribution)
  {:reconciled :removed})

(lifecycle/defresource! attribution-engine
  "Own post-commit attribution reconciliation for the active identity module."
  {:open 'millhouse.spools.identity/open-attribution-engine!
   :close 'millhouse.spools.identity/close-attribution-engine!})

(defn- unique-native-binding [runtime harness native-session-id]
  (let [matches (by-native-session runtime harness native-session-id)]
    (when (< 1 (count matches))
      (fail! "Native harness session has conflicting identity bindings"
             {:harness harness
              :native-session-id native-session-id
              :strand-ids (mapv :id matches)}))
    (first matches)))

(defn- unique-reservation [runtime reservation-id]
  (let [matches (by-reservation-id runtime reservation-id)]
    (when-not (= 1 (count matches))
      (fail! "Identity reservation does not resolve uniquely"
             {:reservation-id reservation-id
              :matches (mapv :id matches)}))
    (first matches)))

(defn- choose [xs]
  (nth xs (.nextInt rng (count xs))))

(defn- candidate []
  (str (choose adjectives) "-" (choose adjectives) "-" (choose nouns)))

(defn- mint-id! [runtime]
  (or (some (fn [_]
              (let [id (candidate)]
                (when (empty? (by-friendly-id runtime id)) id)))
            (range 100))
      (fail! "Could not mint a unique friendly identity" {:attempts 100})))

(defn- with-identity-guard [runtime f]
  (let [config-dir (get-in runtime [:metadata :config-dir])]
    (when-not (and (string? config-dir) (not (str/blank? config-dir)))
      (fail! "Identity binding requires a selected workspace"
             {:config-dir config-dir}))
    (let [lock-file (io/file config-dir "state" ".identity-lock.acquire")]
      (io/make-parents lock-file)
      (locking identity-monitor
        (with-open [file (RandomAccessFile. lock-file "rw")
                    channel (.getChannel file)
                    _lock (.lock channel)]
          (f))))))

(defn- require-run [runtime run-id]
  (when run-id
    (or (weaver/show runtime run-id)
        (fail! "Identity run target not found" {:run-id run-id}))))

(defn- require-parent [runtime parent-identity]
  (when parent-identity
    (current runtime parent-identity)))

(defn- edge-ops [identity-id parent run]
  (cond-> []
    (and parent (not= (:id parent) identity-id))
    (conj {:op :upsert :from :parent :to :identity :type "parent-of"})
    run
    (conj {:op :upsert :from :identity :to :run :type "performed"})))

(defn- mutate-identity!
  [runtime {:keys [identity create patch parent run]}]
  (let [identity-id (:id identity)
        refs (cond-> {}
               identity-id (assoc :identity identity-id)
               parent (assoc :parent (:id parent))
               run (assoc :run (:id run)))
        strands (cond
                  create [{:ref :identity
                           :title (:title create)
                           :attributes (:attributes create)}]
                  patch [{:ref :identity :attributes patch}]
                  :else [])
        edges (edge-ops identity-id parent run)]
    (if (and (empty? strands) (empty? edges))
      identity
      (let [result (batch/apply! runtime {:refs refs
                                          :strands strands
                                          :edges edges
                                          :burn []})]
        (weaver/show runtime (get-in result [:refs :identity]))))))

(defn- instruction [friendly-id]
  (str "Your Millstrand identity is " friendly-id
       ". Use it as `--owner " friendly-id "` for `kanban claim` and `--by-identity "
       friendly-id "` for Kanban notes, workflow mutations, and agent operations. "
       "Keep `--identity` and `--parent-identity` for native-session references. "
       "Inspect live help; never pass an unsupported flag or invent another identity."))

(defn- startup-result [identity result]
  (let [friendly-id (attr-get identity :identity/id)]
    {:identity friendly-id
     :strand-id (:id identity)
     :result result
     :instruction (instruction friendly-id)}))

(defn- attach-under-lock!
  [runtime {:keys [harness native-session-id reservation-id identity
                   parent-identity run-id]}]
  (let [reserved (unique-reservation runtime reservation-id)
        supplied (when identity (current runtime identity))
        native (unique-native-binding runtime harness native-session-id)
        parent (require-parent runtime parent-identity)
        run (require-run runtime run-id)
        reserved-harness (attr-get reserved :identity/harness)
        reserved-session (attr-get reserved :identity/native-session-id)
        reservation-state (attr-get reserved :identity/reservation-state)]
    (when (and supplied (not= (:id supplied) (:id reserved)))
      (fail! "Supplied identity does not match reservation"
             {:identity identity
              :reservation-id reservation-id
              :reserved-identity (attr-get reserved :identity/id)}))
    (when-not (= harness reserved-harness)
      (fail! "Identity reservation belongs to another harness"
             {:reservation-id reservation-id
              :expected-harness reserved-harness
              :actual-harness harness}))
    (when-not (contains? #{"reserved" "attached"} reservation-state)
      (fail! "Identity reservation has invalid state"
             {:reservation-id reservation-id :state reservation-state}))
    (when-not (= (= "attached" reservation-state) (some? reserved-session))
      (fail! "Identity reservation state conflicts with its native binding"
             {:reservation-id reservation-id
              :state reservation-state
              :native-session-id reserved-session}))
    (when (and reserved-session (not= native-session-id reserved-session))
      (fail! "Reserved identity is already attached to another native session"
             {:reservation-id reservation-id
              :identity (attr-get reserved :identity/id)
              :expected-native-session-id reserved-session
              :actual-native-session-id native-session-id}))
    (when (and native (not= (:id native) (:id reserved)))
      (fail! "Native harness session is already bound to another identity"
             {:harness harness
              :native-session-id native-session-id
              :identity (attr-get native :identity/id)
              :reserved-identity (attr-get reserved :identity/id)}))
    (startup-result
     (mutate-identity!
      runtime
      {:identity reserved
       :patch (when-not (and (= "attached" reservation-state)
                             (= native-session-id reserved-session))
                {:identity/native-session-id native-session-id
                 :identity/reservation-state "attached"})
       :parent parent
       :run run})
     "attached")))

(defn attach!
  "Attach a reserved identity to one actual native session exactly once.

  `:reservation-id` is the capability returned by `reserve!`. Replaying the same
  attachment converges; another harness or native session fails before writes."
  [runtime request]
  (require-valid! ::runtime runtime "attach! requires a Weaver runtime")
  (require-valid! ::attach-request request "attach! requires a valid reservation attachment")
  (with-identity-guard runtime #(attach-under-lock! runtime request)))

(defn- startup-under-lock!
  [runtime {:keys [harness native-session-id model thinking-level run-id identity
                   parent-identity reservation-id] :as request}]
  (if reservation-id
    (attach-under-lock! runtime (select-keys request attach-request-keys))
    (let [supplied (when identity (current runtime identity))
          native (unique-native-binding runtime harness native-session-id)
          parent (require-parent runtime parent-identity)
          run (require-run runtime run-id)]
      (when supplied
        (let [supplied-harness (attr-get supplied :identity/harness)
              supplied-session (attr-get supplied :identity/native-session-id)]
          (when (= "reserved" (attr-get supplied :identity/reservation-state))
            (fail! "Reserved identity requires its reservation attachment"
                   {:identity identity}))
          (when-not (and (= harness supplied-harness)
                         (= native-session-id supplied-session))
            (fail! "Supplied identity belongs to another native session"
                   {:identity identity
                    :expected-harness supplied-harness
                    :expected-native-session-id supplied-session
                    :actual-harness harness
                    :actual-native-session-id native-session-id}))))
      (when (and supplied native (not= (:id supplied) (:id native)))
        (fail! "Supplied identity conflicts with the native session binding"
               {:identity identity
                :native-identity (attr-get native :identity/id)
                :harness harness
                :native-session-id native-session-id}))
      (if-let [existing (or supplied native)]
        (startup-result
         (mutate-identity! runtime {:identity existing :parent parent :run run})
         "recovered")
        (let [friendly-id (mint-id! runtime)
              created (mutate-identity!
                       runtime
                       {:create {:title friendly-id
                                 :attributes
                                 (cond-> {:identity/session "true"
                                          :identity/id friendly-id
                                          :identity/harness harness
                                          :identity/native-session-id native-session-id}
                                   model (assoc :identity/model model)
                                   thinking-level
                                   (assoc :identity/thinking-level thinking-level))}
                        :parent parent
                        :run run})]
          (startup-result created "minted"))))))

(defn startup!
  "Resolve identity at native startup without launcher state.

  Requires only `:harness` and the actual `:native-session-id`. An optional
  `:identity` must already name this exact binding. An optional reservation
  attaches through the managed compatibility path. Parent and run targets are
  validated before the transactional identity/provenance write."
  [runtime request]
  (require-valid! ::runtime runtime "startup! requires a Weaver runtime")
  (require-valid! ::startup-request request "startup! requires a valid native session")
  (with-identity-guard runtime #(startup-under-lock! runtime request)))

(defn reserve!
  "Mint an unattached identity reservation for an optional managed caller.

  The returned opaque `:reservation-id` is required to attach the identity; the
  friendly name alone never authorizes attachment."
  [runtime {:keys [harness model thinking-level] :as request}]
  (require-valid! ::runtime runtime "reserve! requires a Weaver runtime")
  (require-valid! ::reserve-request request "reserve! requires a valid reservation")
  (with-identity-guard
    runtime
    (fn []
      (let [friendly-id (mint-id! runtime)
            reservation-id (str (UUID/randomUUID))
            reserved (mutate-identity!
                      runtime
                      {:create {:title friendly-id
                                :attributes
                                (cond-> {:identity/session "true"
                                         :identity/id friendly-id
                                         :identity/harness harness
                                         :identity/reservation-id reservation-id
                                         :identity/reservation-state "reserved"}
                                  model (assoc :identity/model model)
                                  thinking-level
                                  (assoc :identity/thinking-level thinking-level))}})]
        {:identity friendly-id
         :strand-id (:id reserved)
         :result "reserved"
         :reservation-id reservation-id}))))

(defn bind!
  "Compatibility binding for existing managed and maintenance providers.

  Mint/recovery and `:run-id` provenance retain the historical result shape.
  `:expected-identity` remains an assertion: without an existing native binding,
  or on mismatch, it fails before minting or adding edges."
  [runtime {:keys [harness native-session-id expected-identity] :as request}]
  (require-valid! ::runtime runtime "bind! requires a Weaver runtime")
  (require-valid! ::bind-request request "bind! requires a valid session binding")
  (with-identity-guard
    runtime
    (fn []
      (let [native (unique-native-binding runtime harness native-session-id)
            actual (some-> native (attr-get :identity/id))]
        (when (and expected-identity (not= expected-identity actual))
          (fail! "Resumed session identity does not match expectation"
                 {:expected expected-identity
                  :actual actual
                  :harness harness
                  :native-session-id native-session-id}))
        (let [result (startup-under-lock!
                      runtime
                      (cond-> (dissoc request :expected-identity)
                        expected-identity (assoc :identity expected-identity)))]
          {:identity (:identity result)
           :strand-id (:strand-id result)
           :resumed (= "recovered" (:result result))
           :prompt (:instruction result)})))))

(defn codex-child-session-id
  "Return the collision-safe native key for a Codex `(session_id, agent_id)` child.

  Pi callers do not transform IDs: they pass the child's actual native session
  ID directly to `startup!`."
  [parent-session-id agent-id]
  (require-valid! ::native-session-id parent-session-id
                  "Codex child key requires a parent session ID")
  (require-valid! ::native-session-id agent-id
                  "Codex child key requires an agent ID")
  (let [encoder (.withoutPadding (Base64/getUrlEncoder))
        encode #(.encodeToString encoder (.getBytes ^String % StandardCharsets/UTF_8))]
    (str "codex-child:v1:" (encode parent-session-id) ":" (encode agent-id))))

(defn- running-peer [rows matches description]
  (let [found (filterv matches rows)]
    (when-not (and (= 1 (count found)) (:running? (first found)))
      (fail! "Identity registration requires one running Weaver"
             {:selector description :matches (mapv :weaver-id found)}))
    (first found)))

(defn- peer-by-id [rows weaver-id]
  (running-peer rows #(= weaver-id (:weaver-id %)) {:weaver-id weaver-id}))

(defn- local-peer [runtime rows]
  (let [config-dir (get-in runtime [:metadata :config-dir])]
    (require-valid! ::identity config-dir "Identity registration requires a selected workspace")
    (let [workspace (.getCanonicalPath (io/file config-dir))]
      (running-peer rows #(= workspace (.getCanonicalPath (io/file (:workspace %))))
                    {:workspace workspace}))))

(def ^:private descriptor-keys
  [:identity/session :identity/id :identity/harness :identity/native-session-id
   :identity/model :identity/thinking-level])

(defn- descriptor [record friendly-id]
  (when-not (and (identity? record)
                 (= friendly-id (attr-get record :identity/id))
                 (s/valid? ::identity (:id record))
                 (s/valid? ::harness (attr-get record :identity/harness))
                 (s/valid? ::native-session-id (attr-get record :identity/native-session-id))
                 (not= "reserved" (attr-get record :identity/reservation-state)))
    (fail! "Origin identity must have an attached native session"
           {:identity friendly-id :strand-id (:id record)}))
  (into {} (keep (fn [key]
                   (when-some [value (attr-get record key)]
                     [key value]))) descriptor-keys))

(defn- origin-provenance [record origin]
  (let [workspace (attr-get record :identity/origin-workspace)
        strand-id (attr-get record :identity/origin-strand-id)]
    (cond
      (and (nil? workspace) (nil? strand-id))
      {:identity/origin-workspace (:workspace origin)
       :identity/origin-strand-id (:id record)}

      (and (s/valid? ::identity workspace) (s/valid? ::identity strand-id))
      {:identity/origin-workspace workspace
       :identity/origin-strand-id strand-id}

      :else
      (fail! "Origin identity has incomplete registration provenance"
             {:identity (attr-get record :identity/id)
              :origin-workspace workspace
              :origin-strand-id strand-id}))))

(defn- receive-under-lock! [runtime friendly-id attributes]
  (let [matches (by-friendly-id runtime friendly-id)
        existing (first matches)
        native (unique-native-binding runtime (:identity/harness attributes)
                                      (:identity/native-session-id attributes))]
    (when (or (< 1 (count matches))
              (and native (not= (:id native) (:id existing)))
              (and existing
                   (or (= "reserved" (attr-get existing :identity/reservation-state))
                       (not-every? (fn [[key value]] (= value (attr-get existing key)))
                                   attributes))))
      (fail! "Identity registration conflicts with an existing binding or origin"
             {:identity friendly-id :matches (mapv :id matches)
              :native-strand-id (:id native)}))
    (let [record (or existing
                     (mutate-identity! runtime
                                       {:create {:title friendly-id :attributes attributes}}))]
      {:identity friendly-id :strand-id (:id record)
       :result (if existing "existing" "registered")})))

(defn receive!
  "Receive an identity from an exact running origin Weaver ID.

  Transport counterpart to `register!`: fetches `identity show` directly from
  that peer, validates its attached native binding, then atomically creates a
  local descriptor under the identity guard. No caller-supplied descriptor is
  trusted. Copies only session/name/harness/native ID, optional model/thinking,
  and the durable origin workspace and strand ID. Edges and reservations stay
  local. Forwarding an imported descriptor preserves its original provenance.
  Conflicting names, sessions or origin pointers fail without writes."
  [runtime friendly-id from-weaver]
  (require-valid! ::identity friendly-id "receive! requires an identity name")
  (require-valid! ::identity from-weaver "receive! requires an origin Weaver ID")
  (let [rows (peers/peers)
        origin (peer-by-id rows from-weaver)
        target (local-peer runtime rows)]
    (when (= (:workspace origin) (:workspace target))
      (fail! "Identity registration requires a different destination Weaver" {}))
    (let [record (walk/keywordize-keys
                  (peers/call! origin "identity" {:argv ["show" friendly-id]}))
          attributes (merge (descriptor record friendly-id)
                            (origin-provenance record origin))]
      (with-identity-guard runtime #(receive-under-lock! runtime friendly-id attributes)))))

(defn register!
  "Register an existing local identity in an exact destination Weaver ID.

  Run from the origin workspace; Strand owns cwd/workspace discovery. Select
  the destination ID from `mill weaver list`. Both Weavers must be running with
  this identity operation loaded. The destination reads back the origin binding
  before writing; same-host peer discovery is the trust boundary, not user
  authentication. No files, graph edges or reservation capabilities transfer.

  ```text
  strand identity register NAME --to-weaver WEAVER_ID --by-identity NAME
  ```

  `by-identity` must equal `NAME` and resolve in the origin. Returns `:identity`,
  destination `:strand-id`, and `:result` (`registered` or `existing`). Exact
  replay makes no changes; conflicting bindings/provenance fail. Transport errors
  propagate without automatic retry. `identity/origin-workspace` is the durable
  lookup pointer, so an origin Weaver restart does not change the descriptor.
  Registration does not start, restart or reconfigure Weavers."
  [runtime friendly-id to-weaver by-identity]
  (require-valid! ::identity friendly-id "register! requires an identity name")
  (require-valid! ::identity to-weaver "register! requires a destination Weaver ID")
  (require-valid! ::identity by-identity "register! requires an acting identity")
  (when-not (= friendly-id by-identity)
    (fail! "Identity registration must be performed by the identity being registered"
           {:identity friendly-id :by-identity by-identity}))
  (current runtime by-identity)
  (descriptor (current runtime friendly-id) friendly-id)
  (let [rows (peers/peers)
        origin (local-peer runtime rows)
        target (peer-by-id rows to-weaver)]
    (when (= (:workspace origin) (:workspace target))
      (fail! "Identity registration requires a different destination Weaver" {}))
    (dissoc
     (walk/keywordize-keys
      (peers/call! target "identity"
                   {:argv ["receive" friendly-id "--from-weaver" (:weaver-id origin)]}))
     :operation)))

(def ^:private identity-arg-spec
  {:op "identity"
   :doc "Resolve and inspect logical native-session identities."
   :subcommands
   {"register" {:doc "Register this workspace's identity in another running Weaver."
                :hook-class :mutating :deadline-class :standard
                :flags {:to-weaver {:type :string :required? true}
                        :by-identity {:type :string :required? true}}
                :positionals [{:name :friendly-id :type :string :required? true}]}
    "receive" {:doc "Verify and receive an identity from an origin Weaver (transport)."
               :hook-class :mutating :deadline-class :standard
               :flags {:from-weaver {:type :string :required? true}}
               :positionals [{:name :friendly-id :type :string :required? true}]}
    "startup" {:doc "Resolve identity from an actual native session."
               :hook-class :mutating :deadline-class :standard
               :flags {:model {:type :string}
                       :thinking-level {:type :string}
                       :run-id {:type :string}
                       :identity {:type :string}
                       :parent-identity {:type :string}
                       :reservation-id {:type :string}}
               :positionals [{:name :harness :type :string :required? true}
                             {:name :native-session-id :type :string :required? true}]}
    "reserve" {:doc "Reserve an identity for optional managed attachment."
               :hook-class :mutating :deadline-class :standard
               :flags {:model {:type :string}
                       :thinking-level {:type :string}}
               :positionals [{:name :harness :type :string :required? true}]}
    "attach" {:doc "Attach a reservation to one actual native session."
              :hook-class :mutating :deadline-class :standard
              :flags {:run-id {:type :string}
                      :identity {:type :string}
                      :parent-identity {:type :string}}
              :positionals [{:name :harness :type :string :required? true}
                            {:name :native-session-id :type :string :required? true}
                            {:name :reservation-id :type :string :required? true}]}
    "bind" {:doc "Compatibility mint/recover operation."
            :hook-class :mutating :deadline-class :standard
            :flags {:model {:type :string}
                    :thinking-level {:type :string}
                    :run-id {:type :string}
                    :expected-identity {:type :string}}
            :positionals [{:name :harness :type :string :required? true}
                          {:name :native-session-id :type :string :required? true}]}
    "codex-child-key" {:doc "Encode a Codex parent session and child agent key."
                       :hook-class :read :deadline-class :standard
                       :positionals [{:name :parent-session-id :type :string :required? true}
                                     {:name :agent-id :type :string :required? true}]}
    "show" {:doc "Show an identity by its friendly ID."
            :hook-class :read :deadline-class :standard
            :positionals [{:name :friendly-id :type :string :required? true}]}
    "attributions" {:doc "Inspect durable attribution evidence and graph links."
                    :hook-class :read :deadline-class :standard
                    :positionals [{:name :source-id :type :string}]}
    "reconcile" {:doc "Reconcile durable attribution evidence into graph links."
                 :hook-class :mutating :deadline-class :standard
                 :positionals [{:name :source-id :type :string}]}}})

#_{:clj-kondo/ignore [:redefined-var]}
(millstrand/defop! identity
  "Dispatch `strand identity` operations."
  {:arg-spec identity-arg-spec}
  [{:op/keys [runtime args]}]
  (case (first (:subcommand args))
    "register" (register! runtime (:friendly-id args) (:to-weaver args) (:by-identity args))
    "receive" (receive! runtime (:friendly-id args) (:from-weaver args))
    "startup" (startup! runtime (select-keys args startup-request-keys))
    "reserve" (reserve! runtime (select-keys args reserve-request-keys))
    "attach" (attach! runtime (select-keys args attach-request-keys))
    "bind" (bind! runtime (select-keys args bind-request-keys))
    "codex-child-key" {:native-session-id
                       (codex-child-session-id (:parent-session-id args) (:agent-id args))}
    "show" (current runtime (:friendly-id args))
    "attributions" {:attributions
                    (inspect-attributions runtime (some-> (:source-id args) vector))}
    "reconcile" (reconcile-attributions! runtime (some-> (:source-id args) vector))))
