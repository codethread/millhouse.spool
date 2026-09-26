(ns millhouse.workflow-test
  "Test pure workflow compilation, expansion and dependency topology."
  (:require [clojure.test :refer [deftest is testing]]
            [millhouse.workflow :as workflow]))

(deftest workflow-spool-inlines-procedure-calls
  (let [review (workflow/workflow
                "Review"
                (workflow/step :inspect
                               (fn [{:keys [artifact]}] (str "Inspect " artifact)) :self)
                (workflow/step :write-review
                               (fn [{:keys [artifact]}] (str "Write review for " artifact)) :self
                               :depends-on [:inspect]))
        definition (workflow/workflow
                    "Procedure demo"
                    (workflow/step :write-artifact "Write artifact" :self)
                    (workflow/call :review-artifact review {:artifact "proposal.md"}
                                   :depends-on [:write-artifact])
                    (workflow/step :continue "Continue" :self
                                   :depends-on [:review-artifact]))
        payload (workflow/compile definition)
        strands-by-ref (into {} (map (juxt :ref identity)) (:strands payload))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (= #{:molecule :write-artifact :review-artifact--inspect
             :review-artifact--write-review :review-artifact :continue}
           (set (keys strands-by-ref))))
    (is (= "Inspect proposal.md" (get-in strands-by-ref [:review-artifact--inspect :title])))
    (is (contains? edges [:review-artifact--inspect :write-artifact "depends-on"]))
    (is (contains? edges [:review-artifact--write-review :review-artifact--inspect "depends-on"]))
    (is (contains? edges [:review-artifact :review-artifact--write-review "depends-on"]))
    (is (contains? edges [:continue :review-artifact "depends-on"]))))

(deftest workflow-call-compilation-preserves-expanded-payload-shape
  (let [procedure (workflow/workflow
                   "Multi entry"
                   (workflow/step :first "First" :self)
                   (workflow/step :second "Second" :self)
                   (workflow/step :finish-first "Finish first" :self :depends-on [:first])
                   (workflow/step :finish-second "Finish second" :self :depends-on [:second]))
        payload (workflow/compile
                 (workflow/workflow
                  "Caller"
                  (workflow/step :before "Before" :self)
                  (workflow/call :procedure procedure {} :depends-on [:before])))
        strands (into {} (map (juxt :ref identity)) (:strands payload))
        edges (set (map (juxt :from :to :type) (:edges payload)))
        procedure-deps (->> (:edges payload)
                            (filter #(= "depends-on" (:type %)))
                            (filter #(= :procedure (:from %)))
                            (map :to)
                            set)]
    (is (= #{:molecule :before :procedure--first :procedure--second
             :procedure--finish-first :procedure--finish-second :procedure}
           (set (keys strands))))
    (is (contains? edges [:procedure--first :before "depends-on"]))
    (is (contains? edges [:procedure--second :before "depends-on"]))
    (is (contains? edges [:procedure--finish-first :procedure--first "depends-on"]))
    (is (contains? edges [:procedure--finish-second :procedure--second "depends-on"]))
    (is (= #{:procedure--finish-first :procedure--finish-second} procedure-deps))))

(deftest workflow-call-compilation-prefixes-nested-procedures-twice
  (let [inner (workflow/workflow "Inner" (workflow/step :work "Work" :self))
        outer (workflow/workflow "Outer" (workflow/call :inner inner {}))
        payload (workflow/compile
                 (workflow/workflow "Caller" (workflow/call :outer outer {})))
        refs (set (map :ref (:strands payload)))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (= #{:molecule :outer--inner--work :outer--inner :outer} refs))
    (is (contains? edges [:outer--inner :outer--inner--work "depends-on"]))
    (is (contains? edges [:outer :outer--inner "depends-on"]))))

(deftest workflow-call-compilation-preserves-call-title-and-attributes
  (let [payload (workflow/compile
                 (workflow/workflow
                  "Caller"
                  (workflow/call :procedure
                                 (workflow/workflow "Callee" (workflow/step :work "Work" :self))
                                 {}
                                 :title "Review procedure"
                                 :attributes {:owner "reviewer" :priority "high"})))
        join (some #(when (= :procedure (:ref %)) %) (:strands payload))]
    (is (= "Review procedure" (:title join)))
    (is (= "procedure" (get-in join [:attributes "workflow/role"])))
    (is (= "procedure" (get-in join [:attributes "workflow/procedure"])))
    (is (= "reviewer" (get-in join [:attributes :owner])))
    (is (= "high" (get-in join [:attributes :priority])))))

(workflow/defworkflow toastie-quality-workflow
  "Check toastie quality."
  {:entrypoints #{:call}}
  (workflow/workflow
   "Toastie quality check"
   (workflow/step :inspect "Check toastie melt and crunch" :self)))

(workflow/defworkflow cyclic-procedure
  "A procedure that calls itself, to prove expansion refuses a cycle."
  {:entrypoints #{:call}}
  (workflow/workflow "Cyclic procedure"
                     (workflow/step :work "Do work" :self)
                     ;; recursive edge by symbol while the entry call passes the
                     ;; Var: both must canonicalize to one identity
                     (workflow/call :again 'millhouse.workflow-test/cyclic-procedure {}
                                    :depends-on [:work])))

(deftest workflow-compile-fails-loudly-on-cyclic-procedure-call
  ;; conditions filter steps only after procedure expansion, so a cyclic
  ;; procedure reference can never terminate — compile must throw, not overflow
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Workflow procedure call is cyclic"
                        (workflow/compile
                         (workflow/workflow "Cyclic root"
                                            (workflow/call :outer #'cyclic-procedure {}))))))

(deftest workflow-compile-resolves-symbol-procedures
  (let [payload (workflow/compile
                 (workflow/workflow "Symbol procedure demo"
                                    (workflow/call :quality 'millhouse.workflow-test/toastie-quality-workflow {})))]
    (is (= #{:molecule :quality :quality--inspect}
           (set (map :ref (:strands payload)))))))

(deftest workflow-compile-splices-condition-excluded-step-deps
  (let [definition (workflow/workflow
                    "Splice"
                    (workflow/step :design "Design" :self)
                    (workflow/step :review "Review" :self :depends-on [:design] :condition :include-review)
                    (workflow/step :implement "Implement" :self :depends-on [:review]))
        payload (workflow/compile definition)
        refs (set (map :ref (:strands payload)))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (not (contains? refs :review)))
    (is (contains? edges [:implement :design "depends-on"]))
    (is (not (contains? edges [:implement :review "depends-on"])))))

(deftest workflow-compile-splices-transitively-through-two-excluded-steps
  (let [definition (workflow/workflow
                    "Transitive splice"
                    (workflow/step :base "Base" :self)
                    (workflow/step :mid1 "Mid 1" :self :depends-on [:base] :condition :skip)
                    (workflow/step :mid2 "Mid 2" :self :depends-on [:mid1] :condition :skip)
                    (workflow/step :consumer "Consumer" :self :depends-on [:mid2]))
        payload (workflow/compile definition)
        refs (set (map :ref (:strands payload)))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (= #{:molecule :base :consumer} refs))
    (is (contains? edges [:consumer :base "depends-on"]))
    (is (not (contains? edges [:consumer :mid1 "depends-on"])))
    (is (not (contains? edges [:consumer :mid2 "depends-on"])))))

(deftest workflow-compile-fails-loudly-on-unknown-depends-on-ref
  (let [definition (workflow/workflow
                    "Typo"
                    (workflow/step :design "Design" :self)
                    (workflow/step :implement "Implement" :self :depends-on [:desgin]))]
    (try
      (workflow/compile definition)
      (is false "expected compile to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :implement (:step (ex-data e))))
        (is (= :desgin (:missing (ex-data e))))))))

(deftest workflow-compile-attributes-unknown-ref-to-the-excluded-step-that-names-it
  (let [definition (workflow/workflow
                    "Typo in excluded step"
                    (workflow/step :design "Design" :self)
                    (workflow/step :review "Review" :self :depends-on [:desgin] :condition :include-review)
                    (workflow/step :implement "Implement" :self :depends-on [:review]))]
    (try
      (workflow/compile definition)
      (is false "expected compile to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :review (:step (ex-data e))))
        (is (= :desgin (:missing (ex-data e))))))))

(deftest workflow-compile-fails-loudly-on-root-ref-collision
  (let [definition (workflow/workflow
                    "Root collision"
                    (workflow/step :molecule "Steal the root ref" :self))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collides with the root ref"
                          (workflow/compile definition)))))

(deftest workflow-loop-steps-render-item-index-and-params
  (let [definition (workflow/workflow
                    "Loop render"
                    (workflow/step :deploy
                                   (fn [{:keys [feature item i]}]
                                     (str "Deploy " feature " to " (name item) " #" i)) :self
                                   :loop {:each :envs}))
        titles (into {} (map (juxt :ref :title)) (:strands (workflow/compile definition {:feature "checkout"
                                                                                         :envs [:dev :prod]})))]
    (is (= "Deploy checkout to dev #0" (get titles :deploy-1)))
    (is (= "Deploy checkout to prod #1" (get titles :deploy-2)))))

(deftest workflow-loop-each-accepts-param-keyword-and-fn-of-params
  (let [from-keyword (workflow/workflow
                      "Each keyword"
                      (workflow/step :ship (fn [{:keys [item]}] (str "Ship " item)) :self :loop {:each :regions}))
        from-fn (workflow/workflow
                 "Each fn"
                 (workflow/step :ship (fn [{:keys [item]}] (str "Ship " item)) :self
                                :loop {:each (fn [{:keys [regions]}] (reverse regions))}))
        regions {:regions ["us" "eu"]}]
    (is (= #{:molecule :ship-1 :ship-2}
           (set (map :ref (:strands (workflow/compile from-keyword regions))))))
    (is (= ["Ship us" "Ship eu"]
           (mapv :title (rest (:strands (workflow/compile from-keyword regions))))))
    (is (= ["Ship eu" "Ship us"]
           (mapv :title (rest (:strands (workflow/compile from-fn regions))))))))

(deftest workflow-loop-each-fails-loudly-on-non-sequential-param
  (let [definition (workflow/workflow
                    "Bad each"
                    (workflow/step :s "S" :self :loop {:each :n}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":each must resolve to a sequential"
                          (workflow/compile definition {:n 5})))))

(deftest workflow-loop-suffix-rules
  (let [count-def (workflow/workflow "Count" (workflow/step :ping "Ping" :self :loop {:count 3}))
        map-def (workflow/workflow
                 "Map ids"
                 (workflow/step :run (fn [{:keys [item]}] (str "Run " (:id item))) :self :loop {:each :tasks}))
        position-def (workflow/workflow
                      "Positions"
                      (workflow/step :s (fn [{:keys [item]}] (str "S " item)) :self :loop {:each ["x" "y"]}))]
    (is (= [:molecule :ping-1 :ping-2 :ping-3] (map :ref (:strands (workflow/compile count-def)))))
    (is (= [:molecule :run-alpha :run-beta]
           (map :ref (:strands (workflow/compile map-def {:tasks [{:id "alpha"} {:id "beta"}]})))))
    (is (= [:molecule :s-1 :s-2] (map :ref (:strands (workflow/compile position-def)))))))

(deftest workflow-loop-fans-in-base-id-dependents
  (let [definition (workflow/workflow
                    "Fan in"
                    (workflow/step :migrate (fn [{:keys [item]}] (str "Migrate " item)) :self :loop {:each :shards})
                    (workflow/step :verify "Verify migrations" :self :depends-on [:migrate]))
        payload (workflow/compile definition {:shards ["a" "b" "c"]})
        refs (set (map :ref (:strands payload)))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (= #{:molecule :migrate-1 :migrate-2 :migrate-3 :verify} refs))
    (is (contains? edges [:verify :migrate-1 "depends-on"]))
    (is (contains? edges [:verify :migrate-2 "depends-on"]))
    (is (contains? edges [:verify :migrate-3 "depends-on"]))
    ;; the pre-expansion base id is not itself a strand, only its fan-in edges
    (is (not (contains? edges [:verify :migrate "depends-on"])))))

(deftest workflow-loop-does-not-mask-unknown-depends-on-refs
  (let [definition (workflow/workflow
                    "Loop plus typo"
                    (workflow/step :migrate "Migrate" :self :loop {:each :shards})
                    (workflow/step :verify "Verify" :self :depends-on [:migrate :migrat]))]
    (try
      (workflow/compile definition {:shards ["a" "b"]})
      (is false "expected compile to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :verify (:step (ex-data e))))
        (is (= :migrat (:missing (ex-data e))))))))

(deftest workflow-loop-base-id-collisions-fail-loudly
  ;; Fan-in keys deps on the pre-expansion base id, so a base-id collision must
  ;; be rejected before it can silently misroute a dependency.
  (let [dup-base (workflow/workflow
                  "Dup base"
                  (workflow/step :run "Run once" :self :loop {:each :xs})
                  (workflow/step :run "Run again" :self :loop {:count 3}))
        base-vs-plain (workflow/workflow
                       "Base vs plain"
                       (workflow/step :run "Loop" :self :loop {:each :xs})
                       (workflow/step :run "Plain" :self))
        base-vs-root (workflow/workflow
                      "Base vs root"
                      (workflow/step :molecule "Steal root" :self :loop {:each :xs}))
        xs {:xs ["a" "b"]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"step ids must be unique"
                          (workflow/compile dup-base xs)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"step ids must be unique"
                          (workflow/compile base-vs-plain xs)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collides with the root ref"
                          (workflow/compile base-vs-root xs)))))

(deftest workflow-loop-chain-depends-through-expansions-and-keeps-base-fan-in
  (let [definition (workflow/workflow
                    "Chain"
                    (workflow/step :prep "Prep" :self)
                    (workflow/step :task (fn [{:keys [item]}] (str "Task " (:id item))) :self
                                   :depends-on [:prep]
                                   :loop {:each :tasks :chain true})
                    (workflow/step :accept "Accept" :self :depends-on [:task]))
        tasks {:tasks [{:id "a"} {:id "b"} {:id "c"}]}
        edges (set (map (juxt :from :to :type) (:edges (workflow/compile definition tasks))))
        described (into {} (map (juxt :id identity)) (:steps (workflow/describe definition tasks)))]
    (is (contains? edges [:task-a :prep "depends-on"]))
    (is (contains? edges [:task-b :task-a "depends-on"]))
    (is (contains? edges [:task-c :task-b "depends-on"]))
    (is (= [:task-a :task-b :task-c] (:depends-on (described :accept))))))

(deftest workflow-loop-chain-count-uses-previous-count-expansion
  (let [definition (workflow/workflow
                    "Count chain"
                    (workflow/step :round "Round" :self :loop {:count 3 :chain true}))
        edges (set (map (juxt :from :to :type) (:edges (workflow/compile definition))))]
    (is (contains? edges [:round-2 :round-1 "depends-on"]))
    (is (contains? edges [:round-3 :round-2 "depends-on"]))))

(deftest workflow-loop-condition-and-fan-in-splice-interact
  ;; A condition on a loop step is evaluated against workflow params for every
  ;; expanded copy; excluding all copies leaves a base-id dependent to splice
  ;; through the fanned-in (now excluded) ids onto their own deps.
  (let [definition (workflow/workflow
                    "Loop conditions"
                    (workflow/step :migrate "Migrate" :self :loop {:each :shards} :condition :do-migrate)
                    (workflow/step :verify "Verify" :self :depends-on [:migrate]))
        payload (workflow/compile definition {:shards ["a" "b"] :do-migrate false})
        refs (set (map :ref (:strands payload)))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (= #{:molecule :verify} refs))
    (is (not-any? (fn [[_ to _]] (= :migrate to)) edges))))

(deftest compile-stamps-each-defer-with-its-lexical-path
  (let [callee (workflow/bind-defers
                (workflow/workflow "C" (workflow/defer :pick "Pick"))
                {:pick #{:wt-two-step}})
        outer (workflow/workflow "Outer" (workflow/call :c callee {}))
        payload (workflow/compile outer {} {:definition 'millhouse.workflow-test/outer})
        path (get-in (first (filter #(= "defer" (get-in % [:attributes "workflow/role"]))
                                    (:strands payload)))
                     [:attributes "workflow/defer-path"])]
    (is (= ["millhouse.workflow-test/outer" nil] (mapv #(get % "definition") path))
        "the enclosing definition, then the procedure it fixed-called into")
    (is (every? #(re-matches #"[0-9a-f]{16}" (get % "fingerprint")) path))
    (is (apply not= (map #(get % "fingerprint") path))
        "each ancestor is digested as itself, not as the root over again")
    (let [anonymous (workflow/compile
                     (workflow/bind-defers
                      (workflow/workflow "Anonymous" (workflow/defer :pick "Pick"))
                      {:pick #{:wt-two-step}}))
          anonymous-path (get-in (second (:strands anonymous))
                                 [:attributes "workflow/defer-path"])]
      (is (nil? (get-in anonymous-path [0 "definition"])))
      (is (re-matches #"[0-9a-f]{16}" (get-in anonymous-path [0 "fingerprint"]))))))

(deftest sibling-defers-carry-independent-paths
  ;; The path is per defer strand, never a shared root record, which is what lets
  ;; two siblings later select the same target.
  (let [definition (workflow/bind-defers
                    (workflow/workflow
                     "Two points"
                     (workflow/defer :first-pick "First")
                     (workflow/defer :second-pick "Second"))
                    {:first-pick #{:wt-two-step}
                     :second-pick #{:wt-two-step}})
        payload (workflow/compile definition {})
        paths (mapv #(get-in % [:attributes "workflow/defer-path"])
                    (filter #(= "defer" (get-in % [:attributes "workflow/role"]))
                            (:strands payload)))]
    (is (= 2 (count paths)))
    (is (every? some? paths) "each sibling carries its own path, not a shared one")
    (is (apply = paths) "siblings at the same lexical depth share a path value")))

(deftest an-authored-defer-path-cannot-forge-lineage
  ;; PROP-Dfr-001.S5: compile owns the path. An author who supplies one must not
  ;; be able to blank it and walk past the cycle check.
  (testing "the builder refuses the key outright"
    (let [thrown (try (workflow/defer :again "Choose again"
                                      :attributes {"workflow/defer-path" []})
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :workflow/defer-path-reserved (:reason (ex-data thrown))))))
  (testing "and definition validation catches a raw map that skipped it"
    (let [forged {:id :again :title "Choose again"
                  :attributes {"workflow/role" "defer"
                               "workflow/defer" "again"
                               "workflow/defer-workflows" ["wt-two-step"]
                               "workflow/defer-path" []}}
          thrown (try (workflow/workflow "Forged" forged)
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :workflow/defer-path-reserved (:reason (ex-data thrown))))
      (is (= :again (:defer (ex-data thrown))))
      (testing "and compiling the raw map anyway overwrites the authored value"
        (let [payload (workflow/compile {:name "Forged" :steps [forged]} {}
                                        {:definition 'millhouse.workflow-test/forged})
              path (get-in (second (:strands payload))
                           [:attributes "workflow/defer-path"])]
          (is (= ["millhouse.workflow-test/forged"] (mapv #(get % "definition") path))
              "the authored empty ancestry was replaced, not merged into")
          (is (re-matches #"[0-9a-f]{16}" (get-in path [0 "fingerprint"]))))))))
