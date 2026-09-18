(ns millhouse.spools.identity
  "Logical native-session identities and optional run provenance."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.peers.alpha :as peers]
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
       ". Use " friendly-id
       " for identity-bearing operations; pass `--by-identity " friendly-id
       "` explicitly. Do not invent another identity."))

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
            :positionals [{:name :friendly-id :type :string :required? true}]}}})

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
    "show" (current runtime (:friendly-id args))))
