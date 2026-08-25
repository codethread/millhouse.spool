(ns millhouse.spools.millstrand-workflows.bump-spool-test
  "Contract tests for the family-only consumer bump workflow."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millhouse.spools.millstrand-workflows.bootstrap-kondo]
            [millhouse.spools.millstrand-workflows.bump-spool :as bump]
            [millhouse.spools.millstrand-workflows.consumer-tooling :as tooling]
            [millhouse.spools.workflow :as workflow]))

(defn- definition []
  @#'bump/bump-spool)

(defn- step [id]
  (some #(when (= id (:id %)) %) (:steps (definition))))

(defn- instruction [id params]
  (let [value (get-in (step id) [:attributes "workflow/instruction"])]
    (if (fn? value) (value params) value)))

(def ^:private invocation-producer
  {:kind "pinned-remote-family"
   :family "millhouse/spools"
   :coordinate {:git/url "https://github.com/codethread/millhouse.spool.git"
                :git/sha "0123456789012345678901234567890123456789"}})

(deftest params-require-families-and-an-explicit-world
  (let [valid {:families ["io.millstrand/millstrand"]
               :worktree "/tmp/consumer"
               :workspace "/tmp/consumer/.millstrand"
               :invocation-producer {:kind "pinned-remote-family"
                                     :family "millhouse/spools"
                                     :coordinate {:git/url "https://github.com/codethread/millhouse.spool.git"
                                                  :git/sha "0123456789012345678901234567890123456789"}}
               :direct-user-request false}]
    (is (s/valid? ::bump/spool-bump-params valid))
    (is (not (s/valid? ::bump/spool-bump-params
                       (assoc valid :families
                              ["io.millstrand/millstrand"
                               "io.millstrand/millstrand"]))))
    (is (not (s/valid? ::bump/spool-bump-params (dissoc valid :workspace))))))

(deftest bump-instruction-requests-remote-default-branch-head-sha
  (let [instruction (get-in (step :bump-spool) [:attributes "workflow/instruction"])
        text (instruction {:item "io.millstrand/millstrand"
                           :worktree "/tmp/consumer"
                           :workspace "/tmp/consumer/.millstrand"})]
    (is (str/includes? text
                       "strand --workspace /tmp/consumer/.millstrand spool bump io.millstrand/millstrand --latest sha"))
    (is (str/includes? text "already current"))
    (is (str/includes? text "remote default-branch HEAD SHA"))
    (is (not (str/includes? text "--to")))))

(deftest exact-root-evidence-is-captured-before-any-bump
  (let [params {:worktree "/tmp/consumer"
                :workspace "/tmp/consumer/.millstrand"}
        capture-text (instruction :capture-pre-refresh-evidence params)
        bump-text (instruction :bump-spool
                               (assoc params :item "io.millstrand/millstrand"))]
    (is (= [:select-world]
           (:depends-on (step :capture-pre-refresh-evidence))))
    (is (= [:capture-pre-refresh-evidence]
           (:depends-on (step :bump-spool))))
    (doseq [needle ["before any `spool bump`, coordinate edit, or runtime refresh"
                    "strand --workspace /tmp/consumer/.millstrand spool status"
                    "complete structured result"
                    "exact intended family/root set"
                    "`[family root] -> sync.root` map"
                    "Every intended root must have `:status :synced`"
                    "`:bump-pre-refresh-evidence`"]]
      (is (str/includes? capture-text needle)))
    (is (str/includes? bump-text
                       "Require the exact `:bump-pre-refresh-evidence`"))))

(deftest consumer-tooling-child-receives-the-consumer-world
  (let [host-params {:worktree "/tmp/consumer"
                     :workspace "/tmp/workflow-host/.millstrand"
                     :invocation-producer invocation-producer
                     :families ["io.millstrand/millstrand"]
                     :direct-user-request false}
        call (step :configure-consumer-tooling)
        child-params (into {}
                           (map (fn [[key value]] [key (value host-params)]))
                           (:params call))]
    (is (= "/tmp/consumer" (:worktree child-params)))
    (is (= "/tmp/consumer/.millstrand" (:workspace child-params)))
    (is (true? (:inherited-pre-refresh-evidence child-params)))
    (is (not= (:workspace host-params) (:workspace child-params)))))

(deftest bump-loop-captures-tooling-evidence-before-final-refresh
  (let [bump-step (step :bump-spool)
        bootstrap-call (step :bootstrap-kondo)
        tooling-call (step :configure-consumer-tooling)]
    (is (= {:each :families :chain true} (:loop bump-step)))
    (is (= #'millhouse.spools.millstrand-workflows.bootstrap-kondo/bootstrap-kondo
           (:procedure bootstrap-call)))
    (is (true? ((get-in bootstrap-call [:params :inherited-pre-refresh-evidence])
                {})))
    (is (= [:bump-spool] (:depends-on bootstrap-call)))
    (is (= [:configure-consumer-tooling]
           (:depends-on (step :refresh-runtime))))
    (is (= #'tooling/configure-consumer-tooling
           (:procedure tooling-call)))
    (is (= [:bootstrap-kondo] (:depends-on tooling-call)))
    (is (= [:refresh-runtime]
           (:depends-on (step :assess-authorized-cutover))))
    (is (= [:refresh-runtime]
           (:depends-on (step :handover-runtime-generation-evidence))))))

(deftest compiled-capture-precedes-every-bump-and-is-reused-afterward
  (let [params {:families ["io.millstrand/millstrand" "millhouse/spools"]
                :worktree "/tmp/consumer"
                :workspace "/tmp/consumer/.millstrand"
                :invocation-producer invocation-producer
                :direct-user-request false}
        payload (workflow/compile (definition) params)
        strands (into {} (map (juxt :ref identity) (:strands payload)))
        edges (set (map (juxt :from :to :type) (:edges payload)))
        capture-text (get-in strands
                             [:capture-pre-refresh-evidence :attributes
                              "workflow/instruction"])
        inherited-bootstrap-text
        (get-in strands [:bootstrap-kondo--capture-spool-status :attributes
                         "workflow/instruction"])
        inherited-tooling-text
        (get-in strands [:configure-consumer-tooling--inspect-repository :attributes
                         "workflow/instruction"])
        status-command
        "strand --workspace /tmp/consumer/.millstrand spool status"
        instruction-texts
        (keep #(get-in % [:attributes "workflow/instruction"])
              (vals strands))]
    (doseq [edge [[:capture-pre-refresh-evidence :select-world "depends-on"]
                  [:bump-spool-1 :capture-pre-refresh-evidence "depends-on"]
                  [:bump-spool-2 :bump-spool-1 "depends-on"]
                  [:bootstrap-kondo--select-world :bump-spool-2 "depends-on"]]]
      (is (contains? edges edge)))
    (is (str/includes? capture-text status-command))
    (is (= 1 (count (filter #(str/includes? % status-command)
                            instruction-texts))))
    (doseq [text [inherited-bootstrap-text inherited-tooling-text]]
      (is (str/includes? text "`:bump-pre-refresh-evidence`"))
      (is (str/includes? text "Do not run"))
      (is (not (str/includes? text status-command))))))

(deftest runtime-cutover-has-an-explicit-direct-user-boundary
  (is (= [:= :direct-user-request true]
         (:condition (step :assess-authorized-cutover))))
  (is (= [:= :direct-user-request false]
         (:condition (step :handover-runtime-generation-evidence))))
  (is (str/includes? (get-in (step :assess-authorized-cutover)
                             [:attributes "workflow/instruction"])
                     "If no pending generation exists"))
  (is (str/includes? (get-in (step :assess-authorized-cutover)
                             [:attributes "workflow/instruction"])
                     "repeat the selected"))
  (is (str/includes? (get-in (step :handover-runtime-generation-evidence)
                             [:attributes "workflow/instruction"])
                     "no cutover is required"))
  (is (str/includes? (get-in (step :handover-runtime-generation-evidence)
                             [:attributes "workflow/instruction"])
                     "unfinished")))

(deftest family-only-call-compiles-bootstrap-path
  (let [params {:families ["io.millstrand/millstrand"]
                :worktree "/tmp/consumer"
                :workspace "/tmp/consumer/.millstrand"
                :invocation-producer invocation-producer
                :direct-user-request false}
        strands (into {} (map (juxt :ref identity)
                              (:strands (workflow/compile (definition) params))))
        refs (set (keys strands))
        inspect-instruction
        (get-in strands [:configure-consumer-tooling--inspect-repository
                         :attributes "workflow/instruction"])]
    (is (contains? refs :bootstrap-kondo--select-world))
    (is (contains? refs :bootstrap-kondo--adoption-mode))
    (is (contains? refs :refresh-runtime))
    (is (contains? refs :configure-consumer-tooling--inspect-repository))
    (is (contains? refs :configure-consumer-tooling--repository-style))
    (is (str/includes?
         (get-in strands [:bootstrap-kondo--capture-spool-status
                          :attributes "workflow/instruction"])
         "calling bump workflow before its first coordinate mutation"))
    (is (str/includes? inspect-instruction
                       "calling bump workflow before its first coordinate mutation"))
    (is (str/includes? inspect-instruction "/tmp/consumer"))
    (is (str/includes? inspect-instruction "/tmp/consumer/.millstrand"))
    (is (not-any? #(str/includes? (name %) "quality") refs))))
