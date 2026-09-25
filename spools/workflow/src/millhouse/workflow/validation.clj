(ns millhouse.workflow.validation
  "Opt-in validation recipes. Consumers install configuration from a lifecycle resource."
  (:require [clojure.string :as str]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]))

(defn wire-data
  "Normalize persisted nested attribute map keys to their JSON wire spelling."
  [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(if (keyword? k) (subs (str k) 1) k)
                                        (wire-data v)])) x)
    (vector? x) (mapv wire-data x)
    :else x))

(defn- nonblank? [x] (and (string? x) (not (str/blank? x))))

(defn- registry [rt]
  (:config (runtime/spool-state rt ::recipes {:version 1} #(hash-map :config (atom nil)))))

(defn- json-data? [x]
  (cond
    (map? x) (and (every? string? (keys x)) (every? json-data? (vals x)))
    (vector? x) (every? json-data? x)
    :else (or (nil? x) (string? x) (boolean? x) (integer? x))))

(def ^:private frozen-keys
  [:validation/recipe :validation/params :validation/config :validation/request])

(def ^:dynamic *before-images*
  "Transaction-scoped expected rows for the guarded retry operation."
  nil)

(def ^:dynamic *completion*
  "Executor-owned exact gate/attempt pair during terminal success."
  nil)

(defn before-commit
  "Fence retries against transaction pre-images and protect frozen recipe data."
  [ctx]
  (doseq [{:keys [id before after]} (or (:batch/updated ctx)
                                        [{:id (:strand/id ctx)
                                          :before (:strand/before ctx)
                                          :after (:strand/after ctx)}])]
    (when-let [expected (get *before-images* id)]
      (when-not (= expected before)
        (fail! "Validation retry before-image changed"
               {:reason :workflow/validation-stale :gate id})))
    (when (attr-get before :validation/recipe)
      (when-not (= (mapv #(attr-get before %) frozen-keys)
                   (mapv #(attr-get after %) frozen-keys))
        (fail! "Validation recipe and request are frozen"
               {:reason :workflow/validation-frozen :gate id}))
      (when (and (not= "closed" (:state before)) (= "closed" (:state after))
                 (not= *completion* [id (attr-get before :shell/attempt-id)]))
        (fail! "Validation success belongs to the shell executor"
               {:reason :workflow/validation-executor-owned :gate id}))))
  nil)

(defn open!
  "Install closed {:recipes {qualified-versioned-key {:inspect qualified-symbol}}}.

  Call from the consumer's lifecycle resource open callback; return the handle
  to close!. Registration is inert until a future shell gate selects a recipe."
  [rt config]
  (when-not (and (map? config) (= #{:recipes} (set (keys config)))
                 (map? (:recipes config))
                 (every? (fn [[k v]]
                           (and (qualified-keyword? k) (re-find #"-v[0-9]+$" (name k))
                                (map? v) (= #{:inspect} (set (keys v)))
                                (qualified-symbol? (:inspect v))
                                (ifn? (some-> (requiring-resolve (:inspect v)) deref))))
                         (:recipes config)))
    (fail! "Invalid validation recipe configuration"
           {:reason :workflow/validation-config :value config}))
  (when-not (compare-and-set! (registry rt) nil config)
    (fail! "Validation recipes already configured" {:reason :workflow/validation-config}))
  (hooks/register-hook! rt :workflow/validation #{:batch/apply-before-commit :strand/update-before-commit}
                        'millhouse.workflow.validation/before-commit
                        {:order -90 :doc "Fence validation retries and executor-owned success."})
  {:config config})

(defn close!
  "Remove a consumer lifecycle resource's validation configuration."
  [rt handle]
  (when (compare-and-set! (registry rt) (:config handle) nil)
    (hooks/unregister-hook! rt :workflow/validation))
  {:closed :validation})

(defn- recipe [rt identity]
  (or (get-in @(registry rt) [:recipes (keyword identity)])
      (fail! "Validation recipe is not registered"
             {:reason :workflow/validation-unsupported :recipe identity})))

(defn shell-request
  "Project the normal shell request, preserving missing optional fields."
  [attributes]
  (into {} (keep (fn [k]
                   (when-let [v (attr-get {:attributes attributes} k)]
                     [k v])))
        ["shell/argv" "shell/cwd" "shell/timeout-secs"]))

(defn freeze
  "Freeze selected recipe configuration and shell request at pour time."
  [attributes]
  (if-let [identity (get attributes "validation/recipe")]
    (let [identity (if (qualified-keyword? identity) (subs (str identity) 1) identity)
          config (recipe (current/runtime) identity)
          params (get attributes "validation/params" {})]
      (when-not (and (= "shell" (get attributes "workflow/gate"))
                     (json-data? params) (<= (count (pr-str params)) 16384))
        (fail! "Validation opt-in requires a shell gate and bounded JSON params"
               {:reason :workflow/validation-config}))
      (assoc attributes "validation/recipe" identity "validation/params" params
             "validation/config" {"inspect" (str (:inspect config))}
             "validation/request" (shell-request attributes)))
    attributes))

(defn inspect
  "Invoke the read-only recipe at retry, launch or completion; fail closed.

  Returns the closed decision map. An allow requires a nonblank revision;
  whenever expected is supplied it must match, otherwise the result refuses."
  [rt stage run-id gate expected previous]
  (let [identity (attr-get gate :validation/recipe)
        config (recipe rt identity)]
    (when-not (and (= {"inspect" (str (:inspect config))}
                      (wire-data (attr-get gate :validation/config)))
                   (= (shell-request (:attributes gate))
                      (wire-data (attr-get gate :validation/request))))
      (fail! "Frozen validation registration or shell request changed"
             {:reason :workflow/validation-unsupported :recipe identity}))
    (let [result ((requiring-resolve (:inspect config))
                  rt {:stage stage :run-id run-id :gate gate
                      :params (wire-data (attr-get gate :validation/params))
                      :expected-revision expected :previous-attempt previous})]
      (when-not (and (map? result)
                     (every? #{:decision :revision :reason :evidence} (keys result))
                     (contains? #{:allow :refuse :unknown} (:decision result))
                     (nonblank? (:reason result)) (vector? (:evidence result))
                     (json-data? (:evidence result))
                     (<= (count (pr-str (:evidence result))) 16384)
                     (or (nil? (:revision result)) (nonblank? (:revision result)))
                     (or (not= :allow (:decision result)) (nonblank? (:revision result))))
        (fail! "Invalid validation inspection result"
               {:reason :workflow/validation-result :value result}))
      (if (and expected (not= expected (:revision result)))
        (assoc result :decision :refuse :reason "Validation revision changed")
        result))))
