(ns millhouse.harnesses.internal.specs
  "Public harness data specs, registered under `millhouse.harnesses`.

  Kept here so the public namespace can stay a readable story under the
  module-size limit. Callers still write `:millhouse.harnesses/...`."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.harnesses.internal.registry :as registry]))

(s/def :millhouse.harnesses/runtime map?)
(s/def :millhouse.harnesses/name-ref
  #(or (keyword? %) (symbol? %) (and (string? %) (not (str/blank? %)))))
(s/def :millhouse.harnesses/id (s/and string? (complement str/blank?)))
(s/def :millhouse.harnesses/title (s/and string? (complement str/blank?)))
(s/def :millhouse.harnesses/state #{"active" "closed"})
(s/def :millhouse.harnesses/attributes map?)
(s/def :millhouse.harnesses/doc (s/and string? (complement str/blank?)))
(s/def :millhouse.harnesses/effort
  #(or (keyword? %)
       (and (string? %) (not (str/blank? %)))))
(s/def :millhouse.harnesses/model (s/and string? (complement str/blank?)))
(s/def :millhouse.harnesses/append-system-prompt
  (s/and string? (complement str/blank?)))
(s/def :millhouse.harnesses/literal-extra-argv
  (s/coll-of string? :kind vector?))
(s/def :millhouse.harnesses/appended-system-prompts
  (s/coll-of :millhouse.harnesses/append-system-prompt :kind vector?))
(s/def :millhouse.harnesses/strand
  (s/keys :req-un [:millhouse.harnesses/id :millhouse.harnesses/title
                   :millhouse.harnesses/state :millhouse.harnesses/attributes]))
(s/def :millhouse.harnesses/mode #{:headless :interactive "headless" "interactive"})
(s/def :millhouse.harnesses/modes
  (s/coll-of #{:headless :interactive} :kind set? :min-count 1))
(s/def :millhouse.harnesses/callback qualified-symbol?)
(s/def :millhouse.harnesses/prepare :millhouse.harnesses/callback)
(s/def :millhouse.harnesses/finish :millhouse.harnesses/callback)
(s/def :millhouse.harnesses/overlay-attributes
  (s/and map?
         #(every? registry/overlay-key? (keys %))
         #(every? registry/json-value? (vals %))))
(s/def :millhouse.harnesses/harness-definition
  (s/and
   (s/keys :req-un [:millhouse.harnesses/modes
                    :millhouse.harnesses/prepare
                    :millhouse.harnesses/finish]
           :opt-un [:millhouse.harnesses/attributes])
   #(qualified-symbol? (:prepare %))
   #(qualified-symbol? (:finish %))
   #(every? #{:modes :prepare :finish :attributes} (keys %))
   #(or (not (contains? % :attributes))
        (s/valid? :millhouse.harnesses/overlay-attributes (:attributes %)))))
(s/def :millhouse.harnesses/condition
  #(or (s/valid? :millhouse.harnesses/name-ref %)
       (and (vector? %)
            (cond
              (= :not (first %))
              (and (= 2 (count %)) (s/valid? :millhouse.harnesses/condition (second %)))

              (#{:and :or} (first %))
              (and (< 1 (count %))
                   (every? (partial s/valid? :millhouse.harnesses/condition) (rest %)))

              :else false))))
(s/def :millhouse.harnesses/when :millhouse.harnesses/condition)
(s/def :millhouse.harnesses/visibility-names
  (s/and set? #(every? (partial s/valid? :millhouse.harnesses/name-ref) %)))
(s/def :millhouse.harnesses/allow :millhouse.harnesses/visibility-names)
(s/def :millhouse.harnesses/deny :millhouse.harnesses/visibility-names)
(s/def :millhouse.harnesses/env
  (s/map-of (s/and string? #(re-matches #"[A-Za-z_][A-Za-z0-9_]*" %))
            string?))
(s/def :millhouse.harnesses/alias-candidate
  (s/and (s/keys :req-un [:millhouse.harnesses/doc
                          :millhouse.harnesses/parent
                          :millhouse.harnesses/attributes]
                 :opt-un [:millhouse.harnesses/when
                          :millhouse.harnesses/append-system-prompt
                          :millhouse.harnesses/allow
                          :millhouse.harnesses/deny
                          :millhouse.harnesses/env])
         #(every? #{:doc :parent :model :effort :append-system-prompt
                    :attributes :when :allow :deny :env}
                  (keys %))
         #(not (and (contains? % :allow) (contains? % :deny)))
         #(s/valid? :millhouse.harnesses/name-ref (:parent %))
         #(or (not (contains? % :model)) (s/valid? :millhouse.harnesses/model (:model %)))
         #(or (not (contains? % :effort)) (s/valid? :millhouse.harnesses/effort (:effort %)))
         #(s/valid? :millhouse.harnesses/overlay-attributes (:attributes %))))
(s/def :millhouse.harnesses/alias-descriptor
  #(or (s/valid? :millhouse.harnesses/alias-candidate %)
       (and (vector? %) (seq %)
            (every? (partial s/valid? :millhouse.harnesses/alias-candidate) %))))
(s/def :millhouse.harnesses/candidates
  (s/coll-of :millhouse.harnesses/alias-candidate :kind vector? :min-count 1))
(s/def :millhouse.harnesses/alias-result
  (s/keys :req-un [:millhouse.harnesses/alias :millhouse.harnesses/candidates]))
(s/def :millhouse.harnesses/alias string?)
(s/def :millhouse.harnesses/parent :millhouse.harnesses/name-ref)
(s/def :millhouse.harnesses/harness :millhouse.harnesses/name-ref)
(s/def :millhouse.harnesses/definition :millhouse.harnesses/harness-definition)
(s/def :millhouse.harnesses/generated :millhouse.harnesses/overlay-attributes)
(s/def :millhouse.harnesses/resolved-harness
  (s/keys :req-un [:millhouse.harnesses/alias
                   :millhouse.harnesses/harness
                   :millhouse.harnesses/definition
                   :millhouse.harnesses/generated
                   :millhouse.harnesses/env]))
(s/def :millhouse.harnesses/name string?)
(s/def :millhouse.harnesses/kind #{"harness" "alias"})
(s/def :millhouse.harnesses/alias-of string?)
(s/def :millhouse.harnesses/mode-name #{"headless" "interactive"})
(s/def :millhouse.harnesses.registry/modes
  (s/coll-of :millhouse.harnesses/mode-name :kind vector? :min-count 1))
(s/def :millhouse.harnesses/available boolean?)
(s/def :millhouse.harnesses/unavailable-reasons (s/coll-of map? :kind vector?))
(s/def :millhouse.harnesses/selected-candidate nat-int?)
(s/def :millhouse.harnesses/selected-parent string?)
(s/def :millhouse.harnesses/registry-entry
  (s/or :harness
        (s/and (s/keys :req-un [:millhouse.harnesses/name
                                :millhouse.harnesses/kind
                                :millhouse.harnesses.registry/modes
                                :millhouse.harnesses/available]
                       :opt-un [:millhouse.harnesses/harness
                                :millhouse.harnesses/unavailable-reasons])
               #(= "harness" (:kind %))
               #(every? #{:name :kind :modes :available :harness
                          :unavailable-reasons}
                        (keys %)))
        :alias
        (s/and (s/keys :req-un [:millhouse.harnesses/name
                                :millhouse.harnesses/kind
                                :millhouse.harnesses/candidates
                                :millhouse.harnesses/available]
                       :opt-un [:millhouse.harnesses/harness
                                :millhouse.harnesses/selected-candidate
                                :millhouse.harnesses/selected-parent
                                :millhouse.harnesses/unavailable-reasons])
               #(= "alias" (:kind %))
               #(every? #{:name :kind :candidates :available :harness
                          :selected-candidate :selected-parent
                          :unavailable-reasons}
                        (keys %)))))
(s/def :millhouse.harnesses/registry-list
  (s/coll-of :millhouse.harnesses/registry-entry :kind vector?))
(s/def :millhouse.harnesses/harness-registration
  (s/keys :req-un [:millhouse.harnesses/harness :millhouse.harnesses/definition]))
(s/def :millhouse.harnesses/prompt string?)
(s/def :millhouse.harnesses/argv
  (s/and vector?
         seq
         #(and (string? (first %)) (not (str/blank? (first %))))
         #(every? string? %)))
(s/def :millhouse.harnesses/stdin (s/nilable string?))
(s/def :millhouse.harnesses/launch-spec
  (s/and
   (s/keys :req-un [:millhouse.harnesses/argv :millhouse.harnesses/stdin]
           :opt-un [:millhouse.harnesses/env])
   #(every? #{:argv :stdin :env} (keys %))))
(s/def :millhouse.harnesses/cwd (s/and string? (complement str/blank?)))
(s/def :millhouse.harnesses/session-id (s/and string? (complement str/blank?)))
(s/def :millhouse.harnesses/resumes :millhouse.harnesses/id)
(s/def :millhouse.harnesses/after :millhouse.harnesses/id)
(s/def :millhouse.harnesses/by-identity :millhouse.harnesses/id)
(s/def :millhouse.harnesses/target :millhouse.harnesses/id)
(s/def :millhouse.harnesses/root-targets
  (s/coll-of :millhouse.harnesses/id :kind vector? :distinct true))
(s/def :millhouse.harnesses/context (s/and map? registry/json-value?))
(s/def :millhouse.harnesses/request-id (s/and string? (complement str/blank?)))
(s/def :millhouse.harnesses/logical-id :millhouse.harnesses/id)
(s/def :millhouse.harnesses/run-id :millhouse.harnesses/id)
(s/def :millhouse.harnesses/identity :millhouse.harnesses/id)
(s/def :millhouse.harnesses/resume-selector
  (s/and
   (s/keys :opt-un [:millhouse.harnesses/run-id
                    :millhouse.harnesses/session-id
                    :millhouse.harnesses/identity
                    :millhouse.harnesses/logical-id])
   #(= 1 (count (select-keys % [:run-id :session-id :identity
                                :logical-id])))
   #(every? #{:run-id :session-id :identity :logical-id} (keys %))))
(s/def :millhouse.harnesses/resume-selector-intent
  :millhouse.harnesses/resume-selector)
(s/def :millhouse.harnesses/guidance-transport #{:legacy :native-v1 "legacy" "native-v1"})
(s/def :millhouse.harnesses/frozen map?)
(def create-keys
  "Closed key set accepted by `create!`."
  #{:harness :mode :prompt :cwd :attributes :title :resumes :after :session-id
    :append-system-prompt :literal-extra-argv :by-identity :target :root-targets
    :context :request-id :logical-id :frozen :guidance-transport
    :resume-selector-intent})
(s/def :millhouse.harnesses/create-request
  (s/and
   (s/keys :req-un [:millhouse.harnesses/harness]
           :opt-un [:millhouse.harnesses/mode :millhouse.harnesses/prompt
                    :millhouse.harnesses/cwd :millhouse.harnesses/attributes
                    :millhouse.harnesses/title :millhouse.harnesses/resumes
                    :millhouse.harnesses/after
                    :millhouse.harnesses/session-id
                    :millhouse.harnesses/append-system-prompt
                    :millhouse.harnesses/literal-extra-argv
                    :millhouse.harnesses/by-identity
                    :millhouse.harnesses/target :millhouse.harnesses/root-targets
                    :millhouse.harnesses/context
                    :millhouse.harnesses/request-id
                    :millhouse.harnesses/logical-id
                    :millhouse.harnesses/frozen
                    :millhouse.harnesses/guidance-transport
                    :millhouse.harnesses/resume-selector-intent])
   #(every? create-keys (keys %))
   #(or (not (contains? % :attributes))
        (s/valid? :millhouse.harnesses/overlay-attributes (:attributes %)))))
(s/def :millhouse.harnesses/status #{:done :failed "done" "failed"})
(s/def :millhouse.harnesses/exit-code (s/nilable int?))
(s/def :millhouse.harnesses/result (s/nilable string?))
(s/def :millhouse.harnesses/error (s/nilable string?))
(s/def :millhouse.harnesses/session-usable boolean?)
(s/def :millhouse.harnesses/invocation :millhouse.harnesses/id)
(s/def :millhouse.harnesses/settled boolean?)
(s/def :millhouse.harnesses/settlement (s/and string? (complement str/blank?)))
(s/def :millhouse.harnesses/gap string?)
(s/def :millhouse.harnesses/failure-class
  #{"bootstrap" "launch" "execution" "reconciliation"})
(s/def :millhouse.harnesses/cancelled? boolean?)
(s/def :millhouse.harnesses/evidence
  (s/keys :req-un [:millhouse.harnesses/settled :millhouse.harnesses/settlement]
          :opt-un [:millhouse.harnesses/gap :millhouse.harnesses/failure-class
                   :millhouse.harnesses/cancelled?]))
(s/def :millhouse.harnesses/outcome
  (s/and
   (s/keys :req-un [:millhouse.harnesses/status]
           :opt-un [:millhouse.harnesses/exit-code
                    :millhouse.harnesses/result
                    :millhouse.harnesses/session-id
                    :millhouse.harnesses/error
                    :millhouse.harnesses/session-usable
                    :millhouse.harnesses/invocation
                    :millhouse.harnesses/evidence])
   #(every? #{:status :exit-code :result :session-id :error :session-usable
              :invocation :evidence}
            (keys %))))
(s/def :millhouse.harnesses/reason (s/and string? (complement str/blank?)))
(s/def :millhouse.harnesses/stop-request
  (s/and (s/keys :opt-un [:millhouse.harnesses/reason
                          :millhouse.harnesses/by-identity])
         #(every? #{:reason :by-identity} (keys %))))
(s/def :millhouse.harnesses/attempt pos-int?)
(s/def :millhouse.harnesses/started
  (s/keys :req-un [:millhouse.harnesses/strand
                   :millhouse.harnesses/invocation
                   :millhouse.harnesses/attempt]))
(s/def :millhouse.harnesses/eligible? boolean?)
(s/def :millhouse.harnesses/resume-eligibility
  (s/keys :req-un [:millhouse.harnesses/eligible? :millhouse.harnesses/reason]))
(s/def :millhouse.harnesses/retry-request
  (s/and
   (s/keys :opt-un [:millhouse.harnesses/harness
                    :millhouse.harnesses/cwd
                    :millhouse.harnesses/attributes
                    :millhouse.harnesses/guidance-transport
                    :millhouse.harnesses/by-identity])
   #(every? #{:harness :cwd :attributes :guidance-transport :by-identity}
            (keys %))
   #(or (not (contains? % :attributes))
        (s/valid? :millhouse.harnesses/overlay-attributes (:attributes %)))))
(s/def :millhouse.harnesses/resume-request
  (s/and
   (s/keys :opt-un [:millhouse.harnesses/prompt
                    :millhouse.harnesses/cwd
                    :millhouse.harnesses/attributes
                    :millhouse.harnesses/mode
                    :millhouse.harnesses/title
                    :millhouse.harnesses/by-identity
                    :millhouse.harnesses/target
                    :millhouse.harnesses/context
                    :millhouse.harnesses/request-id
                    :millhouse.harnesses/guidance-transport
                    :millhouse.harnesses/resume-selector-intent])
   #(every? #{:prompt :cwd :attributes :mode :title :by-identity :target
              :context :request-id :guidance-transport :resume-selector-intent}
            (keys %))
   #(or (not (contains? % :attributes))
        (s/valid? :millhouse.harnesses/overlay-attributes (:attributes %)))))
