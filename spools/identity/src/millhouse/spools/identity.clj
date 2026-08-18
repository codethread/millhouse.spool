(ns millhouse.spools.identity
  "Logical harness-session identities and run provenance."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.security SecureRandom]))

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
(def ^:private bind-lock (Object.))

(s/def ::runtime map?)
(s/def ::harness (s/and string? (complement str/blank?)))
(s/def ::native-session-id (s/and string? (complement str/blank?)))
(s/def ::model (s/and string? (complement str/blank?)))
(s/def ::thinking-level (s/and string? (complement str/blank?)))
(s/def ::run-id (s/and string? (complement str/blank?)))
(s/def ::expected-identity (s/and string? (complement str/blank?)))
(s/def ::bind-request
  (s/and (s/keys :req-un [::harness ::native-session-id]
                 :opt-un [::model ::thinking-level ::run-id ::expected-identity])
         #(every? #{:harness :native-session-id :model :thinking-level
                    :run-id :expected-identity}
                  (keys %))))

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

(defn- attach-run! [runtime identity run-id]
  (when-not (weaver/show runtime run-id)
    (fail! "Identity run target not found" {:run-id run-id}))
  (weaver/update! runtime (:id identity) {:edges [{:type "performed" :to run-id}]})
  identity)

(defn bind!
  "Mint or recover the identity for one native logical harness session.

  Replays are idempotent. `:expected-identity` makes resume mismatch loud. When
  `:run-id` is supplied, the identity records a `performed` edge to that strand."
  [runtime {:keys [harness native-session-id model thinking-level run-id
                   expected-identity] :as request}]
  (require-valid! ::runtime runtime "bind! requires a Weaver runtime")
  (require-valid! ::bind-request request "bind! requires a valid session binding")
  (locking bind-lock
    (let [matches (by-native-session runtime harness native-session-id)]
      (when (< 1 (count matches))
        (fail! "Native harness session has conflicting identity bindings"
               {:harness harness :native-session-id native-session-id
                :strand-ids (mapv :id matches)}))
      (let [identity (or (first matches)
                         (let [friendly-id (mint-id! runtime)]
                           (weaver/add!
                            runtime
                            {:title friendly-id
                             :attributes
                             (cond-> {:identity/session "true"
                                      :identity/id friendly-id
                                      :identity/harness harness
                                      :identity/native-session-id native-session-id}
                               model (assoc :identity/model model)
                               thinking-level (assoc :identity/thinking-level thinking-level))})))
            friendly-id (attr-get identity :identity/id)]
        (when (and expected-identity (not= expected-identity friendly-id))
          (fail! "Resumed session identity does not match expectation"
                 {:expected expected-identity :actual friendly-id
                  :harness harness :native-session-id native-session-id}))
        (when run-id (attach-run! runtime identity run-id))
        {:identity friendly-id
         :strand-id (:id identity)
         :resumed (boolean (first matches))
         :prompt (str "You are agent " friendly-id
                      ". Use this identity for identity-bearing operations; never invent another identity.")}))))

(defn current
  "Resolve an existing identity by friendly ID, failing when absent or ambiguous."
  [runtime friendly-id]
  (let [matches (by-friendly-id runtime friendly-id)]
    (when-not (= 1 (count matches))
      (fail! "Identity does not resolve uniquely"
             {:identity friendly-id :matches (mapv :id matches)}))
    (first matches)))

(def ^:private identity-arg-spec
  {:op "identity"
   :doc "Bind and inspect logical harness-session identities."
   :subcommands
   {"bind" {:doc "Mint or recover a session identity."
            :hook-class :mutating :deadline-class :standard
            :flags {:model {:type :string}
                    :thinking-level {:type :string}
                    :run-id {:type :string}
                    :expected-identity {:type :string}}
            :positionals [{:name :harness :type :string :required? true}
                          {:name :native-session-id :type :string :required? true}]}
    "show" {:doc "Show an identity by its friendly ID."
            :hook-class :read :deadline-class :standard
            :positionals [{:name :friendly-id :type :string :required? true}]}}})

#_{:clj-kondo/ignore [:redefined-var]}
(millstrand/defop! identity
  "Dispatch `strand identity` operations."
  {:arg-spec identity-arg-spec}
  [{:op/keys [runtime args]}]
  (case (first (:subcommand args))
    "bind" (bind! runtime (select-keys args [:harness :native-session-id :model
                                             :thinking-level :run-id
                                             :expected-identity]))
    "show" (current runtime (:friendly-id args))))
