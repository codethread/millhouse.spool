(ns millhouse.workflow-runtime-test
  "Test workflow transitions, routing, history and attention with fresh worlds."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.test-support :as test-support :refer [with-runtime]]
            [millhouse.workflow :as workflow]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.time Duration Instant]))

(defn- failure-reason [f]
  (:reason (ex-data (try (f) (catch clojure.lang.ExceptionInfo e e)))))

(deftest workflow-spool-compiles-and-materializes-molecules
  (with-runtime
    (fn [rt _]
      (let [with-feature (fn [prefix]
                           (fn [{:keys [feature]}]
                             (str prefix feature)))
            definition (workflow/workflow
                        (with-feature "Ship ")
                        (workflow/step :design (with-feature "Design ") :self
                                       :attributes {:owner (fn [{:keys [owner]}] owner)})
                        (workflow/step :implement (with-feature "Implement ") :self
                                       :depends-on [:design])
                        (workflow/step :review (with-feature "Review ") :self
                                       :depends-on [:implement]
                                       :condition :include-review))
            result (workflow/pour! definition {:feature "workflow spool"
                                               :owner "agent"
                                               :include-review true})
            root-id (workflow/molecule-id result)
            root (weaver/show rt root-id)
            subgraph (graph/subgraph rt [root-id])]
        (is (= "Ship workflow spool" (:title root)))
        (is (= "root" (get-in root [:attributes :workflow/role])))
        (is (= #{"Design workflow spool" "Implement workflow spool" "Review workflow spool" "Ship workflow spool"}
               (set (map :title (:strands subgraph)))))
        (is (= 3 (count (filter #(= "parent-of" (:edge_type %)) (:edges subgraph)))))))))

(workflow/defworkflow toastie-quality-workflow
  "Check toastie quality."
  {:entrypoints #{:call}}
  (workflow/workflow
   "Toastie quality check"
   (workflow/step :inspect "Check toastie melt and crunch" :self)))

(workflow/defworkflow toastie-serve-workflow
  "Serve a toastie."
  {:entrypoints #{:continue}}
  (workflow/workflow
   (fn [{:keys [filling]}] (str "Serve " filling " toastie"))
   (workflow/step :plate (fn [{:keys [filling]}] (str "Plate " filling " toastie")) :self)))

(deftest workflow-spool-runtime-drives-toastie-demo
  (with-runtime
    (fn [_rt _]
      (let [toastie (workflow/workflow
                     (fn [{:keys [filling]}] (str "Make " filling " toastie"))
                     (workflow/step :butter-bread "Butter bread" :self)
                     (workflow/call :quality #'toastie-quality-workflow {}
                                    :depends-on [:butter-bread])
                     (workflow/checkpoint :choose-finish "Choose toastie finish"
                                          :depends-on [:quality]
                                          :kind :agent
                                          :choices [{:key :serve
                                                     :label "Serve"
                                                     :description "Plate the toastie and serve it hot."
                                                     :next 'millhouse.workflow-runtime-test/toastie-serve-workflow}
                                                    {:key :remake
                                                     :label "Remake"
                                                     :description "Start over with fresh bread."}]))]
        (is (= [{:title "Butter bread" :role "step"}]
               (mapv #(select-keys % [:title :role])
                     (:ready (workflow/start! "toastie-demo" toastie {:filling "cheese"})))))
        (is (= "Check toastie melt and crunch" (:title (first (:ready (workflow/complete! "toastie-demo"))))))
        ;; completing the inner quality step auto-closes the procedure join, so
        ;; the checkpoint is next with no manual "Complete quality" step to close
        (is (= "Choose toastie finish" (:title (first (:ready (workflow/complete! "toastie-demo"))))))
        (is (= ["serve" "remake"] (:choices (workflow/ready-step "toastie-demo"))))
        (is (not (contains? (workflow/ready-step "toastie-demo") :choice-details)))
        (is (= {"label" "Serve"
                "description" "Plate the toastie and serve it hot."
                "next" "millhouse.workflow-runtime-test/toastie-serve-workflow"}
               (workflow/choice-detail "toastie-demo" :serve)))
        (is (= "Plate cheese toastie"
               (:title (first (:ready (workflow/choose! "toastie-demo" :serve {:filling "cheese"}))))))
        (is (= {:ready [] :done true} (workflow/complete! "toastie-demo")))
        (is (workflow/done? "toastie-demo"))))))

(def ^:private github-pr-bindings
  {:pr.open           {:instruction "gh pr create --fill"}
   :pr.ci.wait        {:instruction "gh pr checks --watch --fail-fast"
                       :skills "ci-watch"}
   :pr.ci.fix         {:instruction "gh run view --log-failed to inspect the failing checks"}
   :pr.review.wait    {:instruction "gh pr view --comments"}
   :pr.review.address {:instruction "Reply with gh pr comment; push follow-up commits"}
   :pr.merge          {:instruction "gh pr merge --squash"}})

(def ^:private binding-attr-keys
  {:instruction "workflow/instruction"
   :skills "skills"})

(defn- action-binding
  "Return the binding for `action-ref`, failing loudly (TEN-003) on an unbound
  action or a key outside the binding vocabulary — a typo in user bindings must
  not yield a silently bare step."
  [bindings action-ref]
  (let [bindings (or bindings github-pr-bindings)
        bound (or (get bindings action-ref)
                  (throw (ex-info "No binding for workflow action"
                                  {:action-ref action-ref :bound (vec (keys bindings))})))]
    (when-let [unknown (seq (remove binding-attr-keys (keys bound)))]
      (throw (ex-info "Unknown binding keys"
                      {:action-ref action-ref :unknown (vec unknown)
                       :allowed (vec (keys binding-attr-keys))})))
    bound))

(defn- bound
  "Return `action-ref`'s step attributes: its semantic name, plus one render fn
  per binding field.

  The attribute keys are fixed by the vocabulary and the values arrive from the
  `:bindings` param at render time, which is what lets one static definition
  serve every forge — nothing about the binding set is decided when the
  definition is written."
  [action-ref]
  (into {"workflow/action-ref" (name action-ref)}
        (map (fn [[field attr]]
               [attr (fn [{:keys [bindings]}]
                       (get (action-binding bindings action-ref) field))]))
        binding-attr-keys))

(workflow/defworkflow pr-ci-round
  "Wait for CI, then judge the result."
  {:entrypoints #{:continue :call} :defaults {}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "CI round for " feature))
   (workflow/gate :ci-wait (fn [{:keys [feature]}] (str "Wait for CI on " feature)) :ci
                  :attributes (bound :pr.ci.wait))
   (workflow/checkpoint :ci-verdict "Judge CI result"
                        :depends-on [:ci-wait]
                        :kind :agent
                        :choices [{:key :green
                                   :label "CI green"
                                   :description "All checks passed; hand off to review."
                                   :next 'millhouse.workflow-runtime-test/pr-review-round}
                                  {:key :red
                                   :label "CI red"
                                   :description "Checks failed; run the fix-CI loop."
                                   :next 'millhouse.workflow-runtime-test/pr-fix-ci}])))

(workflow/defworkflow pr-fix-ci
  "Diagnose and push a CI fix, then re-run the CI round."
  {:entrypoints #{:continue} :defaults {}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Fix CI for " feature))
   (workflow/step :diagnose "Diagnose CI failure" :self
                  :attributes (bound :pr.ci.fix))
   (workflow/step :push-fix "Push CI fix" :self :depends-on [:diagnose])
   (workflow/call :ci-round #'pr-ci-round {} :depends-on [:push-fix])))

(workflow/defworkflow pr-review-round
  "Wait for reviewer feedback, then judge the review outcome."
  {:entrypoints #{:continue} :defaults {}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Review round for " feature))
   (workflow/gate :review-wait
                  (fn [{:keys [feature]}] (str "Wait for reviewer feedback on " feature))
                  :human
                  :attributes (bound :pr.review.wait))
   (workflow/checkpoint :review-verdict "Judge review outcome"
                        :depends-on [:review-wait]
                        :kind :agent
                        :choices [{:key :approved
                                   :label "Approved"
                                   :description "All green and approved; merge."
                                   :next 'millhouse.workflow-runtime-test/pr-merge}
                                  {:key :changes-requested
                                   :label "Changes requested"
                                   :description "Address comments, push, and re-run CI."
                                   :next 'millhouse.workflow-runtime-test/pr-fix-and-push}])))

(workflow/defworkflow pr-fix-and-push
  "Address review comments, then re-run the CI round."
  {:entrypoints #{:continue} :defaults {}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Address review feedback for " feature))
   (workflow/step :address-comments "Address review comments" :self
                  :attributes (bound :pr.review.address))
   (workflow/call :ci-round #'pr-ci-round {} :depends-on [:address-comments])))

(workflow/defworkflow pr-merge
  "Merge the approved change."
  {:entrypoints #{:continue} :defaults {}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Merge " feature))
   (workflow/step :merge (fn [{:keys [feature]}] (str "Merge " feature)) :self
                  :attributes (bound :pr.merge))))

(workflow/defworkflow pr-dev
  "Implement a change, open it for review, and enter the CI round."
  {:entrypoints #{:start} :defaults {}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Pull request: " feature))
   (workflow/step :dev (fn [{:keys [feature]}] (str "Implement " feature)) :self)
   (workflow/step :open "Open the change for review" :self :depends-on [:dev]
                  :attributes (bound :pr.open))
   (workflow/call :ci-round #'pr-ci-round {} :depends-on [:open])))

(deftest workflow-models-pull-request-flow-without-conditional-edges
  (with-runtime
    (fn [rt _]
      (workflow/start! "pr-flow" #'pr-dev {:feature "pr-42"}
                       {:family "pull-request"
                        :context {:feature "pr-42"}})
      (is (= "Implement pr-42" (:title (workflow/ready-step "pr-flow"))))
      (is (= "Open the change for review" (:title (first (:ready (workflow/complete! "pr-flow"))))))
      ;; the CI round is inlined by call; its gate tells the driver to wait
      ;; (e.g. run a blocking `gh pr checks --watch`), not to do work
      (let [gate (first (:ready (workflow/complete! "pr-flow")))]
        (is (= "Wait for CI on pr-42" (:title gate)))
        (is (= "ci" (:gate gate)))
        (is (= "Judge CI result" (:title (first (:ready (workflow/complete! "pr-flow" {:by-identity "ci-bot"}))))))
        (is (= "ci-bot" (get-in (weaver/show rt (:id gate)) [:attributes :identity/by-identity]))))
      ;; red verdict routes into the fix-CI loop, which recomposes the CI round
      (is (= "Diagnose CI failure" (:title (first (:ready (workflow/choose! "pr-flow" :red))))))
      (is (= "Push CI fix" (:title (first (:ready (workflow/complete! "pr-flow"))))))
      (is (= "Wait for CI on pr-42" (:title (first (:ready (workflow/complete! "pr-flow"))))))
      (is (= "Judge CI result" (:title (first (:ready (workflow/complete! "pr-flow" {:by-identity "ci-bot"}))))))
      ;; green verdict hands off to the review round
      (let [review-gate (first (:ready (workflow/choose! "pr-flow" :green)))]
        (is (= "Wait for reviewer feedback on pr-42" (:title review-gate)))
        (is (= "human" (:gate review-gate))))
      (is (= "Judge review outcome" (:title (first (:ready (workflow/complete! "pr-flow" {:by-identity "reviewer"}))))))
      ;; changes requested: fix-and-push recomposes the same CI round, whose
      ;; green verdict flows back into review — the nested loop the flow needs
      (is (= "Address review comments" (:title (first (:ready (workflow/choose! "pr-flow" :changes-requested))))))
      (is (= "Wait for CI on pr-42" (:title (first (:ready (workflow/complete! "pr-flow"))))))
      (is (= "Judge CI result" (:title (first (:ready (workflow/complete! "pr-flow" {:by-identity "ci-bot"}))))))
      (is (= "Wait for reviewer feedback on pr-42" (:title (first (:ready (workflow/choose! "pr-flow" :green))))))
      (is (= "Judge review outcome" (:title (first (:ready (workflow/complete! "pr-flow" {:by-identity "reviewer"}))))))
      ;; approval routes to merge; the run closes itself when merge completes
      (is (= "Merge pr-42" (:title (first (:ready (workflow/choose! "pr-flow" :approved {} {:by-identity "agent-driver"}))))))
      (is (= {:ready [] :done true} (workflow/complete! "pr-flow")))
      (is (workflow/done? "pr-flow")))))

(def ^:private gitlab-pr-bindings
  ;; what a gitlab user writes in their own config: a partial override
  ;; deep-merged over the shipped reference — only the rebound fields of the
  ;; rebound actions change (:pr.ci.wait keeps its reference :skills)
  (merge-with merge github-pr-bindings
              {:pr.open    {:instruction "glab mr create --fill"}
               :pr.ci.wait {:instruction "glab ci status --live"}}))

(deftest workflow-pr-flow-rebinds-forge-without-spool-changes
  (with-runtime
    (fn [_rt _]
      ;; reference run: no bindings passed, the github reference applies
      (workflow/start! "pr-forge-ref" #'pr-dev {:feature "ref-feat"}
                       {:family "pull-request" :context {:feature "ref-feat"}})
      (workflow/complete! "pr-forge-ref")
      (let [open-step (workflow/ready-step "pr-forge-ref")]
        (is (= "pr.open" (:action-ref open-step)))
        (is (= "gh pr create --fill" (:instruction open-step))))
      (let [gate (first (:ready (workflow/complete! "pr-forge-ref")))]
        (is (= "pr.ci.wait" (:action-ref gate)))
        (is (= "gh pr checks --watch --fail-fast" (:instruction gate)))
        (is (= "ci-watch" (:skills gate))))
      ;; gitlab run: the same untouched definitions, driven by user-supplied
      ;; pure-data overrides passed through params and context
      (workflow/start! "pr-forge-gl" #'pr-dev
                       {:feature "gl-feat" :bindings gitlab-pr-bindings}
                       {:family "pull-request"
                        :context {:feature "gl-feat" :bindings gitlab-pr-bindings}})
      (workflow/complete! "pr-forge-gl")
      (is (= "glab mr create --fill" (:instruction (workflow/ready-step "pr-forge-gl"))))
      (let [gate (first (:ready (workflow/complete! "pr-forge-gl")))]
        (is (= "pr.ci.wait" (:action-ref gate)))
        (is (= "glab ci status --live" (:instruction gate)))
        ;; per-field override: only :instruction was rebound, the reference
        ;; :skills field on the same action survives
        (is (= "ci-watch" (:skills gate))))
      ;; red verdict routes into the fix loop: the non-overridden fix action
      ;; keeps the github reference (partial override at work)
      (workflow/complete! "pr-forge-gl" {:by-identity "gitlab-ci"})
      (let [diagnose (first (:ready (workflow/choose! "pr-forge-gl" :red)))]
        (is (= "Diagnose CI failure" (:title diagnose)))
        (is (= "pr.ci.fix" (:action-ref diagnose)))
        (is (= "gh run view --log-failed to inspect the failing checks"
               (:instruction diagnose))))
      (workflow/complete! "pr-forge-gl")
      ;; the rebound CI gate survives the routed loop round: bindings rode
      ;; workflow/context into the recompiled continuation
      (let [gate (first (:ready (workflow/complete! "pr-forge-gl")))]
        (is (= "Wait for CI on gl-feat" (:title gate)))
        (is (= "glab ci status --live" (:instruction gate)))))))

(deftest workflow-runtime-closes-empty-runs-at-start
  (with-runtime
    (fn [_rt _]
      (let [empty-workflow (workflow/workflow "Nothing to do")]
        (is (= {:ready [] :done true} (workflow/start! "empty-run" empty-workflow {})))
        (is (workflow/done? "empty-run"))
        (is (nil? (workflow/current-root "empty-run")))
        (is (= {:ready [] :done true} (workflow/start! "empty-run" empty-workflow {})))))))

(deftest workflow-run-not-done-while-blocked-by-external-dependency
  (with-runtime
    (fn [rt _]
      (let [blocker (weaver/add! rt {:title "External blocker"})
            definition (workflow/workflow
                        "Blocked run"
                        (workflow/step :a "Do A" :self)
                        (workflow/step :b "Do B" :self :depends-on [:a]))
            result (workflow/pour! definition {} {:run-id "blocked-run"})
            b-id (get-in result [:refs :b])]
        (weaver/update! rt b-id {:edges [{:type "depends-on" :to (:id blocker)}]})
        (is (= {:title "Do A" :role "step"}
               (select-keys (workflow/ready-step "blocked-run") [:title :role])))
        (is (= {:ready [] :done false} (workflow/complete! "blocked-run")))
        (is (not (workflow/done? "blocked-run")))
        (is (some? (workflow/current-root "blocked-run")))
        (is (= "active" (:state (weaver/show rt b-id))))))))

(deftest workflow-done-fails-loudly-for-unknown-run
  (with-runtime
    (fn [_rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow run"
                            (workflow/done? "no-such-run"))))))

(deftest workflow-run-auto-closes-root-when-last-step-completes
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow
                        "Linear run"
                        (workflow/step :a "Do A" :self)
                        (workflow/step :b "Do B" :self :depends-on [:a]))]
        (workflow/start! "linear-run" definition {})
        (is (= [{:title "Do B" :role "step"}]
               (mapv #(select-keys % [:title :role]) (:ready (workflow/complete! "linear-run")))))
        (is (= {:ready [] :done true} (workflow/complete! "linear-run")))
        (is (workflow/done? "linear-run"))
        (is (nil? (workflow/current-root "linear-run")))))))

(deftest workflow-runtime-supports-parallel-ready-steps
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow
                        "Parallel entry"
                        (workflow/step :a "Do A" :self)
                        (workflow/step :b "Do B" :self))
            started (:ready (workflow/start! "parallel-run" definition {}))
            a-id (:id (first (filter #(= "Do A" (:title %)) started)))
            b-id (:id (first (filter #(= "Do B" (:title %)) started)))]
        (is (= #{"Do A" "Do B"} (set (map :title started))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Multiple workflow steps are ready"
                              (workflow/complete! "parallel-run")))
        (is (= "active" (:state (weaver/show rt a-id))))
        (is (= "active" (:state (weaver/show rt b-id))))
        (let [remaining (:ready (workflow/complete! "parallel-run" {:step a-id}))]
          (is (= "closed" (:state (weaver/show rt a-id))))
          (is (= "active" (:state (weaver/show rt b-id))))
          (is (= [{:title "Do B" :role "step"}]
                 (mapv #(select-keys % [:title :role]) remaining))))))))

(deftest workflow-complete-merges-caller-attributes-onto-the-closed-step
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow "Attrs run" (workflow/step :a "Do A" :self))
            [step] (:ready (workflow/start! "attrs-run" definition {}))]
        (workflow/complete! "attrs-run" {:attributes {"acme/outcome" "ok"
                                                      "acme/exit-code" 7}})
        (let [strand (weaver/show rt (:id step))]
          (is (= "closed" (:state strand)))
          (is (= "ok" (get-in strand [:attributes :acme/outcome])))
          ;; a typed value survives the merge as itself, not as its printed form
          (is (= 7 (get-in strand [:attributes :acme/exit-code]))))))))

(deftest workflow-complete-context-is-shallow-last-write-wins
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow
                        "Context run"
                        (workflow/step :a "Do A" :self)
                        (workflow/step :b "Do B" :self :depends-on [:a]))
            [step] (:ready (workflow/start! "context-run" definition
                                            {:owner "old"
                                             :nested {:keep true}}))]
        (workflow/complete! "context-run"
                            {:context {:owner "new"
                                       :nested {:replacement true}
                                       :result :passed}})
        (is (= "closed" (:state (weaver/show rt (:id step)))))
        (is (= {:owner "new"
                :nested {:replacement true}
                :result "passed"}
               (get-in (workflow/current-root "context-run")
                       [:attributes :workflow/context]))
            "last write wins shallowly, including replacing a nested value")))))

(deftest workflow-context-preserves-qualified-keyword-values-on-the-wire
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow
                        "Qualified context run"
                        (workflow/step :a "Do A" :self))
            [step] (:ready (workflow/start! "qualified-context-run" definition
                                            {:branch :vcs/branch
                                             :nested {:source :forge/github}}))
            root-id (:id (workflow/current-root "qualified-context-run"))]
        (is (= {:branch "vcs/branch"
                :nested {:source "forge/github"}}
               (get-in (weaver/show rt root-id) [:attributes :workflow/context]))
            "persisted context keeps the namespace in qualified keyword values")
        (workflow/complete! "qualified-context-run"
                            {:context {:decision :review/approved}})
        (is (= "closed" (:state (weaver/show rt (:id step)))))
        (is (= {:branch "vcs/branch"
                :nested {:source "forge/github"}
                :decision "review/approved"}
               (get-in (weaver/show rt root-id) [:attributes :workflow/context]))
            "complete context merge preserves qualified keyword values too")))))

(defn reject-complete-batch-hook
  "Reject the complete batch to exercise transaction rollback."
  [ctx]
  (throw (ex-info "complete batch rejected" {:code "policy/rejected" :ctx ctx})))

(deftest workflow-complete-context-and-step-close-rollback-together
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow
                        "Rejected context run"
                        (workflow/step :a "Do A" :self)
                        (workflow/step :b "Do B" :self :depends-on [:a]))
            [step] (:ready (workflow/start! "rejected-context-run" definition
                                            {:owner "before"}))
            root-id (:id (workflow/current-root "rejected-context-run"))]
        (hooks/register-hook! rt :reject-complete #{:batch/apply-before-commit}
                              'millhouse.workflow-runtime-test/reject-complete-batch-hook {})
        (let [thrown (try
                       (workflow/complete! "rejected-context-run"
                                           {:context {:owner "after"}})
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (= "Lifecycle hook failed" (ex-message thrown)))
          (is (= "policy/rejected" (:hook/cause-code (ex-data thrown)))))
        (is (= "active" (:state (weaver/show rt (:id step)))))
        (is (= {:owner "before"}
               (get-in (weaver/show rt root-id) [:attributes :workflow/context])))))))

(deftest workflow-complete-requires-keyword-context-keys
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow
                        "Context keys"
                        (workflow/step :a "Do A" :self)
                        (workflow/step :b "Do B" :self))
            [step] (:ready (workflow/start! "context-keys-run" definition {}))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid workflow complete context"
                              (workflow/complete! "context-keys-run"
                                                  {:context {"owner" "agent"}})))
        (is (= "active" (:state (weaver/show rt (:id step)))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"cannot be defaulted into workflow/context"
                              (workflow/complete! "context-keys-run"
                                                  {:context {:opaque (Object.)}})))
        (is (= "active" (:state (weaver/show rt (:id step)))))
        (is (not (s/valid? :millhouse.workflow.request/context
                           {:opaque (Object.)})))))))

(deftest workflow-complete-refuses-malformed-persisted-context-before-mutating
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow
                        "Malformed context"
                        (workflow/step :a "Do A" :self)
                        (workflow/step :b "Do B" :self :depends-on [:a]))
            [step] (:ready (workflow/start! "malformed-context-run" definition {}))
            root-id (:id (workflow/current-root "malformed-context-run"))]
        (weaver/update! rt root-id {:attributes {"workflow/context" "not-a-map"}})
        (let [thrown (try
                       (workflow/complete! "malformed-context-run"
                                           {:context {:owner "agent"}})
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/context-invalid (:reason (ex-data thrown))))
          (is (= "malformed-context-run" (:run-id (ex-data thrown))))
          (is (= root-id (:root (ex-data thrown))))
          (is (= "not-a-map" (:context (ex-data thrown)))))
        (is (= "active" (:state (weaver/show rt (:id step)))))
        (is (= "not-a-map"
               (get-in (weaver/show rt root-id) [:attributes :workflow/context])))))))

(deftest workflow-complete-holds-direct-callers-to-the-attributes-spec
  ;; The worker CLI validates its request map; a direct Clojure caller reaches
  ;; the same mutation, so the same spec judges it rather than a looser local check.
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow "Bad attrs run" (workflow/step :a "Do A" :self))
            [step] (:ready (workflow/start! "bad-attrs-run" definition {}))]
        (doseq [[label attributes] [["a non-map" "acme/outcome=ok"]
                                    ["a keyword key" {:acme/outcome "ok"}]
                                    ["a blank key" {"" "ok"}]]]
          (testing label
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Invalid workflow complete attributes"
                                  (workflow/complete! "bad-attrs-run" {:attributes attributes})))))
        (is (= "active" (:state (weaver/show rt (:id step)))))
        (testing "an empty map is a stated no-op, not a rejection"
          (workflow/complete! "bad-attrs-run" {:attributes {}})
          (is (= "closed" (:state (weaver/show rt (:id step))))))))))

(deftest workflow-complete-and-advance-refuse-removed-notes-arg
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow "No notes run"
                                          (workflow/step :a "Do A" :self)
                                          (workflow/step :b "Do B" :self))
            [step] (:ready (workflow/start! "no-notes-run" definition {}))]
        (doseq [[label f] [["complete!" #(workflow/complete! "no-notes-run" {:notes "prose"})]
                           ["advance!" #(workflow/advance! "no-notes-run" {:notes "prose"})]]]
          (testing label
            (try
              (f)
              (is false (str "expected " label " to refuse :notes"))
              (catch clojure.lang.ExceptionInfo e
                (is (re-find #"no longer accepts :notes" (ex-message e)))
                (is (= :workflow/notes-removed (:reason (ex-data e))))
                (is (= label (:op (ex-data e))))))))
        ;; the refusal happens before the guard, so nothing moved
        (is (= "active" (:state (weaver/show rt (:id step)))))))))

(deftest workflow-run-history-reads-legacy-outcome-notes-as-an-ordinary-attribute
  (with-runtime
    (fn [rt _]
      ;; a step closed before the outcome cutover: run-history projects the
      ;; engine's own outcome keys and leaves the historical row on the strand,
      ;; where show and the query language read it like any other attribute.
      (let [definition (workflow/workflow "Legacy run" (workflow/step :a "Do A" :self))
            [step] (:ready (workflow/start! "legacy-notes-run" definition {}))]
        (workflow/complete! "legacy-notes-run" {:attributes {"workflow/outcome-notes" "closed in 2026"}})
        (let [event (first (:events (first (workflow/run-history "legacy-notes-run"))))]
          (is (= :step-closed (:type event)))
          (is (not (contains? event :notes))))
        (is (= "closed in 2026"
               (get-in (weaver/show rt (:id step)) [:attributes :workflow/outcome-notes])))))))

(deftest workflow-complete-fails-loudly-on-invalid-step-and-mutates-nothing
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow "Bad step run" (workflow/step :a "Do A" :self))]
        (workflow/start! "bad-step-run" definition {})
        (let [a-id (:id (workflow/ready-step "bad-step-run"))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Requested workflow step is not ready"
                                (workflow/complete! "bad-step-run" {:step "no-such-step"})))
          (is (= "active" (:state (weaver/show rt a-id)))))))))

(deftest workflow-gate-requires-actor-or-executor-and-records-provenance
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow
                        "Gated run"
                        (workflow/step :push "Push branch" :self)
                        (workflow/gate :ci "Wait for CI to go green" :ci :depends-on [:push])
                        (workflow/step :deploy "Deploy" :self :depends-on [:ci]))]
        (workflow/start! "gated-run" definition {})
        ;; the non-gate :push step closes without :by-identity, reaching the gate
        (let [gate (first (:ready (workflow/complete! "gated-run")))
              gate-id (:id gate)]
          (is (= "ci" (:gate gate)))
          (is (= "step" (:role gate)))
          ;; the gate refuses to close without :by-identity and stays active
          (try
            (workflow/complete! "gated-run")
            (is false "expected gate complete to fail without :by-identity")
            (catch clojure.lang.ExceptionInfo e
              (is (re-find #"Gate steps require actor or executor provenance" (ex-message e)))
              (is (= "ci" (:gate (ex-data e))))
              (is (= "ci" (get-in (ex-data e) [:step :gate])))))
          ;; a nil or blank :by-identity is no better than a missing one
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"completion provenance must be a non-blank string"
                                (workflow/complete! "gated-run" {:by-identity nil})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"completion provenance must be a non-blank string"
                                (workflow/complete! "gated-run" {:by-identity "  "})))
          (is (= "active" (:state (weaver/show rt gate-id))))
          ;; an external actor closes the gate with :by-identity; :deploy becomes ready
          (let [remaining (:ready (workflow/complete! "gated-run" {:by-identity "ci"
                                                                   :attributes {"ci/result" "green"}}))
                closed (weaver/show rt gate-id)]
            (is (= "closed" (:state closed)))
            (is (= "ci" (get-in closed [:attributes :identity/by-identity])))
            (is (= "green" (get-in closed [:attributes :ci/result])))
            (is (= [{:title "Deploy" :role "step"}]
                   (mapv #(select-keys % [:title :role]) remaining)))))))))

(deftest workflow-completion-api-separates-executor-and-run-id-from-identity
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow
                        "Adapter gate"
                        (workflow/gate :agent "Await delegated run" :agent))
            gate (first (:ready (workflow/start! "adapter-run" definition {})))
            result (workflow/run-complete!
                    {:run-id "adapter-run"
                     :step (:id gate)
                     :executor "agent"
                     :executor-run-id "unresolved-run-42"})
            closed (weaver/show rt (:id gate))]
        (is (true? (:done result)))
        (is (= "agent" (get-in closed [:attributes :workflow/executor])))
        (is (= "unresolved-run-42"
               (get-in closed [:attributes :workflow/executor-run-id])))
        (is (nil? (get-in closed [:attributes :identity/by-identity]))
            "an opaque Harnesses run ID is never recorded as identity evidence")
        (is (thrown? clojure.lang.ExceptionInfo
                     (workflow/run-complete!
                      {:run-id "adapter-run"
                       :step (:id gate)
                       :executor-run-id "orphan-run"})))))))

(deftest workflow-non-gate-step-closes-without-provenance
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow "Plain run" (workflow/step :a "Do A" :self))
            [step] (:ready (workflow/start! "plain-gate-run" definition {}))]
        (is (= {:ready [] :done true} (workflow/complete! "plain-gate-run")))
        (let [closed (weaver/show rt (:id step))]
          (is (= "closed" (:state closed)))
          (is (nil? (get-in closed [:attributes :identity/by-identity]))))))))

(deftest workflow-non-gate-step-records-actor-when-supplied
  ;; :by-identity is recorded on any step completion when supplied (provenance parity),
  ;; even though only gates require it
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow "Plain run with by" (workflow/step :a "Do A" :self))
            [step] (:ready (workflow/start! "plain-by-run" definition {}))]
        (is (= {:ready [] :done true}
               (workflow/complete! "plain-by-run"
                                   {:by-identity "not-registered-worker"})))
        (let [closed (weaver/show rt (:id step))]
          (is (= "closed" (:state closed)))
          (is (= "not-registered-worker"
                 (get-in closed [:attributes :identity/by-identity]))))))))

(workflow/defworkflow empty-continuation-workflow
  "Finish a routed run without new work."
  {:entrypoints #{:continue}}
  (workflow/workflow "Empty continuation"))

(deftest workflow-routed-choice-closes-workless-continuation-run
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow
                        "Route to empty"
                        (workflow/checkpoint :route "Route somewhere"
                                             :choices [{:key :finish
                                                        :label "Finish"
                                                        :next 'millhouse.workflow-runtime-test/empty-continuation-workflow}]))]
        (workflow/start! "route-to-empty" definition {})
        (is (= {:ready [] :done true} (workflow/choose! "route-to-empty" :finish)))
        (is (true? (workflow/done? "route-to-empty")))
        (is (nil? (workflow/current-root "route-to-empty")))))))

(workflow/defworkflow routed-continuation-workflow
  "Continue a routed run."
  {:entrypoints #{:continue}}
  (workflow/workflow
   "Continuation"
   (workflow/step :follow-up "Do follow up work" :self)))

(deftest workflow-routed-choice-swaps-to-single-active-continuation-root
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow
                        "Route to work"
                        (workflow/checkpoint :route "Route somewhere"
                                             :choices [{:key :continue
                                                        :label "Continue"
                                                        :next 'millhouse.workflow-runtime-test/routed-continuation-workflow}]))]
        (workflow/start! "route-to-work" definition {})
        (let [old-root-id (:id (workflow/current-root "route-to-work"))
              remaining (:ready (workflow/choose! "route-to-work" :continue))]
          (is (= "closed" (:state (weaver/show rt old-root-id))))
          (is (= [{:title "Do follow up work" :role "step"}]
                 (mapv #(select-keys % [:title :role]) remaining)))
          ;; current-root throws on more than one active root, so a non-nil
          ;; result asserts exactly one active root remains for the run-id
          (let [new-root (workflow/current-root "route-to-work")]
            (is (some? new-root))
            (is (not= old-root-id (:id new-root)))
            (is (= "active" (:state new-root)))))))))

(deftest workflow-choose-records-actor-identity
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow
                        "Signoff run"
                        (workflow/checkpoint :approve "Approve it"
                                             :choices [{:key :approved :label "Approve"}]))
            [step] (:ready (workflow/start! "signoff-run" definition {}))]
        (workflow/choose! "signoff-run" :approved {} {:by-identity "agent:reviewer"})
        (let [strand (weaver/show rt (:id step))]
          (is (= "closed" (:state strand)))
          (is (= "approved" (get-in strand [:attributes :workflow/outcome])))
          (is (= "agent:reviewer" (get-in strand [:attributes :identity/by-identity]))))))))

(defn- loopy-body
  "The shared body of the loopy stage and its revision round.

  Both are the same steps under different defaults, which is what a revision
  round IS now that a definition carries its own defaults."
  []
  (workflow/workflow
   "Loopy"
   (workflow/step :orient "Orient" :self :condition [:!= :revision true])
   (workflow/step :work "Do work" :self :depends-on [:orient])
   (workflow/checkpoint :signoff "Sign off"
                        :depends-on [:work]
                        :kind :agent
                        :choices [{:key :approved :label "Approve"}
                                  {:key :revise
                                   :label "Revise"
                                   :next 'millhouse.workflow-runtime-test/loopy-revision}])))

(workflow/defworkflow loopy
  "A stage whose sign-off can route into a revision round."
  {:entrypoints #{:start :continue} :defaults {}}
  (loopy-body))

(workflow/defworkflow loopy-revision
  "The revision round of `loopy`: the same steps with :revision already true."
  {:entrypoints #{:continue} :defaults {:revision true}}
  (loopy-body))

(deftest workflow-start-accepts-var-and-defaults-durable-context
  (with-runtime
    (fn [_rt _]
      (workflow/start! "var-start" #'loopy {:revision :yes})
      (let [root (workflow/current-root "var-start")]
        (is (= "millhouse.workflow-runtime-test/loopy"
               (get-in root [:attributes :workflow/definition])))
        (is (= {:revision "yes"}
               (get-in root [:attributes :workflow/context]))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pass :context explicitly"
                            (workflow/start! "bad-context" #'loopy {:f identity})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-finite numbers are not JSON-safe"
                            (workflow/start! "nan-context" #'loopy {:n ##NaN})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-finite numbers are not JSON-safe"
                            (workflow/start! "inf-context" #'loopy {:n ##Inf}))))))

(deftest workflow-start-accepts-registered-keyword
  (with-runtime
    (fn [_rt _]
      (workflow/register-workflow! :loopy-test 'millhouse.workflow-runtime-test/loopy)
      (workflow/start! "keyword-start" :loopy-test {})
      (is (= "millhouse.workflow-runtime-test/loopy"
             (get-in (workflow/current-root "keyword-start") [:attributes :workflow/definition])))
      (is (= "Orient" (:title (workflow/ready-step "keyword-start")))))))

(deftest workflow-revise-choice-loops-back-to-a-fresh-revision-round
  (with-runtime
    (fn [rt _]
      (is (= [{:title "Orient" :role "step"}]
             (mapv #(select-keys % [:title :role])
                   (:ready (workflow/start! "loopy" #'loopy {})))))
      (is (= [{:title "Do work" :role "step"}]
             (mapv #(select-keys % [:title :role]) (:ready (workflow/complete! "loopy")))))
      (is (= [{:title "Sign off" :role "checkpoint"}]
             (mapv #(select-keys % [:title :role]) (:ready (workflow/complete! "loopy")))))
      (let [signoff (workflow/ready-step "loopy")
            signoff-id (:id signoff)
            old-root-id (:id (workflow/current-root "loopy"))]
        (is (= "checkpoint" (:role signoff)))
        ;; revise routes back to a fresh revision round under the same run-id
        (let [remaining (:ready (workflow/choose! "loopy" :revise))]
          (is (= "closed" (:state (weaver/show rt signoff-id))))
          (is (= "revise" (get-in (weaver/show rt signoff-id) [:attributes :workflow/outcome])))
          (is (= "closed" (:state (weaver/show rt old-root-id))))
          (let [new-root (workflow/current-root "loopy")]
            (is (some? new-root))
            (is (not= old-root-id (:id new-root))))
          ;; :orient is condition-skipped on the revision round, so :work is ready
          (is (= [{:title "Do work" :role "step"}]
                 (mapv #(select-keys % [:title :role]) remaining))))
        (is (= [{:title "Sign off" :role "checkpoint"}]
               (mapv #(select-keys % [:title :role]) (:ready (workflow/complete! "loopy")))))
        (is (= {:ready [] :done true} (workflow/choose! "loopy" :approved)))
        (is (workflow/done? "loopy"))))))

(deftest workflow-routed-choose-failure-keeps-run-resumable
  (with-runtime
    (fn [rt _]
      (workflow/start! "loopy-fail" #'loopy {})
      (workflow/complete! "loopy-fail")
      (workflow/complete! "loopy-fail")
      (let [old-root-id (:id (workflow/current-root "loopy-fail"))
            signoff-id (:id (workflow/ready-step "loopy-fail"))]
        ;; a failed continuation apply must not leave the run in a false
        ;; terminal state; the checkpoint close and continuation pour are folded
        ;; into one batch/apply!, so a failing apply commits nothing
        (with-redefs [batch/apply! (fn [_ _] (throw (ex-info "batch boom" {})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"batch boom"
                                (workflow/choose! "loopy-fail" :revise))))
        (let [root (workflow/current-root "loopy-fail")]
          (is (some? root))
          (is (= old-root-id (:id root)))
          (is (= "active" (:state root))))
        (is (= "active" (:state (weaver/show rt signoff-id))))
        (is (false? (workflow/done? "loopy-fail")))
        ;; the run stays resumable: retrying the same choice now succeeds
        (is (= [{:title "Do work" :role "step"}]
               (mapv #(select-keys % [:title :role])
                     (:ready (workflow/choose! "loopy-fail" :revise)))))))))

(deftest workflow-runtime-selects-among-parallel-ready-checkpoints
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow
                        "Parallel checkpoints"
                        (workflow/checkpoint :x "Pick X"
                                             :choices [{:key :go :label "Go X"}])
                        (workflow/checkpoint :y "Pick Y"
                                             :choices [{:key :go :label "Go Y"}]))
            started (:ready (workflow/start! "parallel-checkpoints" definition {}))
            x-id (:id (first (filter #(= "Pick X" (:title %)) started)))
            y-id (:id (first (filter #(= "Pick Y" (:title %)) started)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Multiple workflow steps are ready"
                              (workflow/choose! "parallel-checkpoints" :go)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Multiple workflow steps are ready"
                              (workflow/choice-details "parallel-checkpoints")))
        (is (= "active" (:state (weaver/show rt x-id))))
        (is (= "active" (:state (weaver/show rt y-id))))
        ;; choice-details string-keys choice names and detail maps, agreeing
        ;; with choice-detail's shape (archived workflow-engine review, R2)
        (is (= {"go" {"label" "Go X"}}
               (workflow/choice-details "parallel-checkpoints" {:step x-id})))
        (is (= {"label" "Go Y"}
               (workflow/choice-detail "parallel-checkpoints" :go {:step y-id})))
        (let [remaining (:ready (workflow/choose! "parallel-checkpoints" :go {} {:step x-id}))]
          (is (= "closed" (:state (weaver/show rt x-id))))
          (is (= "go" (get-in (weaver/show rt x-id) [:attributes :workflow/outcome])))
          (is (= "active" (:state (weaver/show rt y-id))))
          (is (= [y-id] (mapv :id remaining))))))))

(deftest workflow-spool-supports-wisps-bonds-and-squash
  (with-runtime
    (fn [rt _]
      (let [left-result (workflow/wisp! {:name "Left" :steps [{:id :a :title "A"}]})
            right-result (workflow/wisp! {:name "Right" :steps [{:id :b :title "B"}]})
            left-id (workflow/molecule-id left-result)
            right-id (workflow/molecule-id right-result)]
        (is (= "wisp" (get-in (weaver/show rt left-id) [:attributes :workflow/form])))
        (workflow/bond! left-id right-id)
        (let [digest (workflow/squash! left-id "Left digest" {:summary "done"})]
          (is (= "closed" (:state digest)))
          (is (= "digest" (get-in digest [:attributes :workflow/role])))
          (is (nil? (weaver/show rt left-id))))))))

(deftest workflow-bond-parent-blocks-the-bonded-run
  (with-runtime
    (fn [_rt _]
      (workflow/start! "bond-left" {:name "Left" :steps [{:id :a :title "Do A"}]} {})
      (workflow/start! "bond-right" {:name "Right" :steps [{:id :b :title "Do B"}]} {})
      (let [left-root-id (:id (workflow/current-root "bond-left"))
            right-root-id (:id (workflow/current-root "bond-right"))]
        (workflow/bond! left-root-id right-root-id)
        ;; the right step has no deps of its own, but the dep-blocked root
        ;; hides the whole run until the left root closes
        (is (= [] (workflow/ready "bond-right")))
        (is (false? (workflow/done? "bond-right")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No ready workflow step"
                              (workflow/complete! "bond-right")))
        (is (= "Do A" (:title (workflow/ready-step "bond-left"))))
        (workflow/complete! "bond-left")
        (is (true? (workflow/done? "bond-left")))
        (is (= ["Do B"] (mapv :title (workflow/ready "bond-right"))))))))

(deftest workflow-ready-bounds-storage-query-to-current-subgraph
  (with-runtime
    (fn [rt _]
      (let [unrelated-id (:id (weaver/add! rt {:title "Unrelated ready work"}))
            calls (atom [])
            real-ready weaver/ready
            started (with-redefs [weaver/ready
                                  (fn [runtime query-def params]
                                    (swap! calls conj {:query-def query-def
                                                       :params params})
                                    (real-ready runtime query-def params))]
                      (workflow/start! "bounded-run"
                                       {:name "Bounded"
                                        :steps [{:id :work :title "Bounded work"}]}
                                       {}))
            root-id (:id (workflow/current-root "bounded-run"))
            selected-ids (set (map :id (:strands (graph/subgraph rt [root-id]))))
            call (first @calls)]
        (is (= 1 (count @calls)))
        (is (= [:in :id selected-ids] (:query-def call)))
        (is (= {} (:params call)))
        (is (not (contains? selected-ids unrelated-id)))
        (is (= ["Bounded work"] (mapv :title (:ready started))))))))

(deftest workflow-run-scoped-views-carry-run-id-and-filter-frontier
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow "Runid demo"
                                          (workflow/step :a "Do A" :self)
                                          (workflow/gate :handoff "Hand off" :agent)
                                          (workflow/checkpoint :decide "Decide" :kind :agent :choices [:ok]))
            started (workflow/start! "runid-run" definition {})]
        (is (= "runid-run" (:run-id (first (:ready started)))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Multiple workflow steps are ready"
                              (workflow/ready-step "runid-run")))
        (is (= #{"Do A" "Hand off" "Decide"} (set (map :title (workflow/ready "runid-run")))))
        (is (= ["runid-run" "runid-run" "runid-run"] (mapv :run-id (workflow/ready "runid-run"))))
        (is (= ["Hand off"] (mapv :title (workflow/ready-gates "runid-run" "agent"))))
        (is (= "Decide" (:title (workflow/ready-checkpoint "runid-run"))))
        (is (= ["Decide"] (mapv :title (workflow/ready "runid-run" {:role "checkpoint"}))))
        ;; a bare step-view (no run context) stays unchanged
        (is (not (contains? (workflow/step-view {:id "x" :title "T" :state "active"
                                                 :attributes {"workflow/role" "step"}})
                            :run-id)))))))

(workflow/defworkflow join-inner-workflow
  "Perform the inner joined procedure."
  {:entrypoints #{:call}}
  (workflow/workflow
   "Inner"
   (workflow/step :do-inner "Do inner work" :self)))

(deftest workflow-procedure-join-auto-closes-and-never-surfaces-as-ready
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow
                        "Join demo"
                        (workflow/step :prep "Prep" :self)
                        (workflow/call :inner #'join-inner-workflow {} :depends-on [:prep])
                        (workflow/step :after "After" :self :depends-on [:inner]))]
        (workflow/start! "join-run" definition {})
        (is (= "Prep" (:title (workflow/ready-step "join-run"))))
        ;; completing prep reveals the inner step, not the join
        (is (= "Do inner work" (:title (first (:ready (workflow/complete! "join-run"))))))
        ;; completing the last inner step auto-closes the join in the same
        ;; transaction: the join never appears as ready work and :after is next
        (let [after-inner (:ready (workflow/complete! "join-run"))]
          (is (= ["After"] (mapv :title after-inner)))
          (is (not-any? #(= "procedure" (:role %)) after-inner)))
        ;; the join strand is closed with engine provenance, though it was never
        ;; returned as a ready step nor manually completed
        (let [join (first (weaver/list rt [:and
                                           [:= [:attr "workflow/role"] "procedure"]
                                           [:= [:attr "workflow/procedure"] "inner"]]
                                       {}))]
          (is (= "closed" (:state join)))
          (is (= "engine" (get-in join [:attributes :workflow/executor])))
          (is (nil? (get-in join [:attributes :identity/by-identity]))))
        (is (= {:ready [] :done true} (workflow/complete! "join-run")))
        (is (workflow/done? "join-run"))))))

(deftest workflow-advance-drives-steps-and-checkpoints
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow
                        "Advance demo"
                        (workflow/step :work "Do work" :self)
                        (workflow/checkpoint :sign "Sign off"
                                             :depends-on [:work]
                                             :kind :agent
                                             :choices [{:key :approved :label "Approve"}]))]
        (workflow/start! "advance-run" definition {})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow advance opts"
                              (workflow/advance! "advance-run" {:step 42})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                              (workflow/advance! "advance-run" {:bogus true})))
        (is (= :workflow/advance-input-without-checkpoint
               (failure-reason #(workflow/advance! "advance-run"
                                                   {:input {:verdict "pass"}}))))
        (let [step-id (:id (workflow/ready-step "advance-run"))]
          (weaver/update! rt step-id {:attributes {"workflow/role" "improvised"}})
          (is (= :workflow/ready-next-incompatible
                 (failure-reason #(workflow/advance! "advance-run"
                                                     {:step step-id}))))
          (is (= "active" (:state (weaver/show rt step-id))))
          (weaver/update! rt step-id {:attributes {"workflow/role" "step"}}))
        ;; a ready step advanced with a :choice fails loudly and mutates nothing
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not supply a :choice"
                              (workflow/advance! "advance-run" {:choice :approved})))
        ;; advance! completes the ready step, returning the D1.1 result shape
        (let [after (workflow/advance! "advance-run")]
          (is (= ["Sign off"] (mapv :title (:ready after))))
          (is (false? (:done after))))
        ;; a ready checkpoint advanced without a :choice fails loudly
        (let [thrown (try (workflow/advance! "advance-run")
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (re-find #"requires a :choice" (ex-message thrown)))
          (is (= ["approved"] (:choices (ex-data thrown)))))
        (is (= :workflow/advance-attributes-on-checkpoint
               (failure-reason
                #(workflow/advance! "advance-run"
                                    {:choice :approved
                                     :attributes {"verdict" "pass"}}))))
        ;; advance! dispatches the checkpoint choice and closes the run
        (is (= {:ready [] :done true} (workflow/advance! "advance-run" {:choice :approved})))
        (is (workflow/done? "advance-run"))
        (testing "a non-inferable sibling does not make one advanceable item ambiguous"
          (workflow/start! "advance-with-gate"
                           (workflow/workflow
                            "Advance beside gate"
                            (workflow/step :work "Do work" :self)
                            (workflow/gate :wait "Wait" :external))
                           {})
          (is (= ["Wait"]
                 (mapv :title (:ready (workflow/advance! "advance-with-gate")))))
          (let [thrown (try (workflow/advance! "advance-with-gate" {:by-identity "ci-bot"})
                            (catch clojure.lang.ExceptionInfo e e))]
            (is (= :workflow/ready-next-absent (:reason (ex-data thrown))))
            (is (= ["Wait"] (mapv :title (:ready (ex-data thrown)))))
            (is (re-find #"--step" (:guidance (ex-data thrown))))))))))

(defn- registry-router-stage [{:keys [target]}]
  (workflow/workflow
   "Registry router"
   (workflow/checkpoint :go "Go"
                        :kind :agent
                        :choices [{:key :advance :label "Advance" :next target}])))

(workflow/defworkflow registry-second-stage
  "Provide the second registry stage."
  {:entrypoints #{:continue}}
  (workflow/workflow "Registry second" (workflow/step :do-second "Do second" :self)))

(workflow/defworkflow revise-stage-workflow
  "A stage whose sign-off can re-pour itself with :revision true."
  {:entrypoints #{:start :continue} :defaults {}}
  (workflow/workflow
   "Revise stage"
   (workflow/step :orient "Orient" :self :condition [:!= :revision true])
   (workflow/checkpoint :signoff "Sign off"
                        :depends-on [:orient]
                        :kind :agent
                        :choices [{:key :revise :label "Revise" :revise {:params {:revision true}}}
                                  {:key :approved :label "Approve" :next :wt-downstream}])))

(workflow/defworkflow downstream-stage-workflow
  "Provide the downstream stage."
  {:entrypoints #{:continue}}
  (workflow/workflow "Downstream stage" (workflow/step :do-downstream "Do downstream" :self)))

(deftest workflow-routing-refuses-malformed-persisted-context-before-mutating
  (with-runtime
    (fn [rt _]
      (workflow/register-workflow! :wt-second 'millhouse.workflow-runtime-test/registry-second-stage)
      (workflow/register-workflow! :wt-downstream
                                   'millhouse.workflow-runtime-test/downstream-stage-workflow)
      (doseq [[run-id definition choice]
              [["malformed-next" (registry-router-stage {:target :wt-second}) :advance]
               ["malformed-revise" #'revise-stage-workflow :revise]]]
        (workflow/start! run-id definition {} {:context {}})
        (when (= choice :revise)
          (workflow/complete! run-id))
        (let [root-id (:id (workflow/current-root run-id))
              checkpoint-id (:id (workflow/ready-step run-id))]
          (weaver/update! rt root-id {:attributes {"workflow/context" "not-a-map"}})
          (let [thrown (try
                         (workflow/choose! run-id choice)
                         (catch clojure.lang.ExceptionInfo e e))]
            (is (= :workflow/context-invalid (:reason (ex-data thrown))))
            (is (= run-id (:run-id (ex-data thrown))))
            (is (= root-id (:root (ex-data thrown))))
            (is (= "not-a-map" (:context (ex-data thrown)))))
          (is (= "active" (:state (weaver/show rt checkpoint-id))))
          (is (= "not-a-map"
                 (get-in (weaver/show rt root-id)
                         [:attributes :workflow/context]))))))))

(deftest workflow-revise-repours-definition-skipping-condition-gated-steps
  (with-runtime
    (fn [_rt _]
      (workflow/register-workflow! :wt-downstream 'millhouse.workflow-runtime-test/downstream-stage-workflow)
      (workflow/start! "revise-run" #'revise-stage-workflow {} {:context {}})
      (is (= "Orient" (:title (workflow/ready-step "revise-run"))))
      (is (= [{:title "Sign off" :role "checkpoint"}]
             (mapv #(select-keys % [:title :role]) (:ready (workflow/complete! "revise-run")))))
      ;; :revise re-pours the run's own workflow/definition with :revision true;
      ;; the condition-gated :orient drops out, so signoff is immediately ready
      (is (= [{:title "Sign off" :role "checkpoint"}]
             (mapv #(select-keys % [:title :role]) (:ready (workflow/choose! "revise-run" :revise)))))
      (let [revised-root (workflow/current-root "revise-run")]
        (is (true? (get-in revised-root [:attributes :workflow/context :revision])))
        ;; the override key is recorded stage-local so it can be shed on exit
        (is (= ["revision"] (get-in revised-root [:attributes :workflow/stage-params]))))
      ;; approving routes forward: the stage-local :revision must not leak into
      ;; the downstream stage's persisted context
      (let [remaining (:ready (workflow/choose! "revise-run" :approved))]
        (is (= ["Do downstream"] (mapv :title remaining)))
        (is (not (contains? (get-in (workflow/current-root "revise-run")
                                    [:attributes :workflow/context])
                            :revision)))))))

(deftest workflow-revise-fails-loudly-without-resolvable-definition
  (with-runtime
    (fn [_rt _]
      ;; no :definition seeded, so the run's root cannot resolve a workflow to
      ;; re-pour and :revise fails loudly (TEN-003) rather than guessing
      ;; started from a raw value, so the root records no workflow/definition
      (workflow/start! "revise-nodef" @#'revise-stage-workflow {})
      (workflow/complete! "revise-nodef")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no workflow/definition"
                            (workflow/choose! "revise-nodef" :revise))))))

(workflow/defworkflow introspect-stage-b
  "The stage an approved introspection round hands off to."
  {:entrypoints #{:continue}}
  (workflow/workflow
   "Introspect stage B"
   (workflow/step :finish "Finish B" :self)))

(s/def ::reason string?)

(s/def ::revise-reason-input (s/keys :req-un [::reason]))

(s/def ::introspect-params (s/keys :req-un [::feature]))

(workflow/defworkflow introspect-stage-a
  "A stage carrying a conditioned step, a routed choice, and a revision round."
  {:entrypoints #{:start :continue}
   :param-spec ::introspect-params
   :defaults {}}
  (workflow/workflow
   "Introspect stage A"
   (workflow/step :draft (fn [{:keys [feature]}] (str "Draft " feature)) :self
                  :condition [:!= :revision true])
   (workflow/step :refine "Refine draft" :self :depends-on [:draft])
   (workflow/checkpoint :signoff "Sign off"
                        :depends-on [:refine]
                        :kind :agent
                        :choices [{:key :approve
                                   :label "Approve"
                                   :description "Ship it."
                                   :next 'millhouse.workflow-runtime-test/introspect-stage-b}
                                  {:key :revise
                                   :label "Revise"
                                   :description "Send it back."
                                   :revise {:params {:revision true}}
                                   :input {:spec ::revise-reason-input
                                           :doc "Why revise"}}])))

(deftest workflow-run-history-projects-ordered-molecules-and-events
  (with-runtime
    (fn [rt _]
      (workflow/start! "hist" #'introspect-stage-a {:feature "widgets"}
                       {:context {:feature "widgets"}})
      (workflow/complete! "hist")                            ; :draft
      (workflow/complete! "hist" {:attributes {"acme/round" "one"}}) ; :refine
      (workflow/choose! "hist" :revise {:reason "needs work"}) ; loop → round 2
      (workflow/complete! "hist" {:attributes {"acme/round" "two"}}) ; :refine (draft skipped)
      (workflow/choose! "hist" :approve {})                  ; hand off → stage B
      (workflow/complete! "hist")                            ; :finish → done
      (is (workflow/done? "hist"))
      (let [history (workflow/run-history "hist")
            created (map #(get-in % [:root :created_at]) history)
            choice-outcome (fn [mol] (some #(when (= :choice (:type %)) (:outcome %)) (:events mol)))
            revise-mol (first (filter #(= "revise" (choice-outcome %)) history))
            approve-mol (first (filter #(= "approve" (choice-outcome %)) history))
            stage-b-mol (first (filter #(= "Introspect stage B" (get-in % [:root :title])) history))
            ;; the engine projects its own outcome keys only, so a caller's
            ;; vocabulary is read back off the closed strands the events name
            round-set (fn [mol]
                        (set (keep #(get-in (weaver/show rt (:id %)) [:attributes :acme/round])
                                   (:events mol))))]
        (is (= 3 (count history)))
        ;; molecules are ordered by creation; events within a molecule by :at
        (is (= created (sort created)))
        (is (every? (fn [{:keys [events]}] (= (map :at events) (sort (map :at events)))) history))
        ;; the revise round recorded the choice input and the first round's attrs
        (is (= "Introspect stage A" (get-in revise-mol [:root :title])))
        (is (= {:reason "needs work"}
               (:input (first (filter #(= :choice (:type %)) (:events revise-mol))))))
        (is (contains? (round-set revise-mol) "one"))
        (is (contains? (round-set approve-mol) "two"))
        ;; the conditioned :draft ran only in the first round
        (is (some #(= "Draft widgets" (:title %)) (:events revise-mol)))
        (is (not-any? #(= "Draft widgets" (:title %)) (:events approve-mol)))
        (is (= [:step-closed] (mapv :type (:events stage-b-mol))))))))

(deftest workflow-run-history-fails-loudly-for-unknown-run
  (with-runtime
    (fn [_rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow run"
                            (workflow/run-history "no-such-run"))))))

(deftest workflow-squash-run-refuses-active-then-squashes-to-one-digest
  (with-runtime
    (fn [rt _]
      (workflow/start! "arch" #'introspect-stage-a {:feature "widgets"}
                       {:context {:feature "widgets"}})
      (workflow/complete! "arch")             ; :draft
      (workflow/complete! "arch")             ; :refine
      ;; an active root cannot be archived
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"active root"
                            (workflow/squash-run! "arch")))
      (workflow/choose! "arch" :approve {})   ; hand off → stage B
      (workflow/complete! "arch")             ; :finish → done
      (is (workflow/done? "arch"))
      (let [digest (workflow/squash-run! "arch")
            summary (get-in digest [:attributes :workflow/summary])
            molecules (weaver/list rt [:and [:= [:attr "workflow/run-id"] "arch"]
                                       [:= [:attr "workflow/role"] "root"]] {})
            digests (weaver/list rt [:and [:= [:attr "workflow/run-id"] "arch"]
                                     [:= [:attr "workflow/role"] "digest"]] {})]
        (is (= "closed" (:state digest)))
        (is (= "digest" (get-in digest [:attributes :workflow/role])))
        (is (= "arch" (get-in digest [:attributes :workflow/run-id])))
        ;; the summary carries stage titles + checkpoint outcomes
        (is (= 2 (count summary)))
        (is (contains? (set (map :title summary)) "Introspect stage A"))
        (is (contains? (set (mapcat :outcomes summary)) "approve"))
        ;; exactly one digest remains for the run and every molecule is burned
        (is (empty? molecules))
        (is (= 1 (count digests)))))))

(deftest await-returns-checkpoint-for-a-ready-checkpoint
  (with-runtime
    (fn [_rt _]
      (workflow/start! "await-checkpoint"
                       (workflow/workflow "Await checkpoint"
                                          (workflow/checkpoint :decide "Decide" :kind :human
                                                               :choices [:go]))
                       {})
      (is (= :checkpoint (:reason (workflow/await! "await-checkpoint" {:timeout-secs 1})))))))

(deftest await-returns-step-for-a-ready-self-step
  ;; a bare :self step used to bury itself under :waiting; it must now surface
  ;; immediately as :step so the driving agent never sits idle on its own work
  (with-runtime
    (fn [_rt _]
      (workflow/start! "await-self-step"
                       (workflow/workflow "Await step" (workflow/step :do-it "Do it" :self))
                       {})
      (is (= :step (:reason (workflow/await! "await-self-step" {:timeout-secs 1})))))))

(deftest await-returns-gate-for-a-waiter-with-no-registered-executor
  (with-runtime
    (fn [_rt _]
      (workflow/start! "await-unowned-gate"
                       (workflow/workflow "Await gate"
                                          (workflow/gate :delegate "Delegate" :await-test-unowned))
                       {})
      (is (= :gate (:reason (workflow/await! "await-unowned-gate" {:timeout-secs 1})))))))

(deftest await-stays-silent-on-a-healthy-executor-owned-gate-then-reports-stalled
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow "Await executor gate"
                                          (workflow/gate :delegate "Delegate" :await-test-executor))]
        (workflow/start! "await-executor-gate" definition {})
        (test-alpha/set-clock! rt (test-alpha/manual-clock Instant/EPOCH))
        (let [gate-id (:id (first (workflow/ready "await-executor-gate")))]
          (is (= :await-test-executor
                 (workflow/register-executor! :await-test-executor (constantly nil))))
          ;; a healthy executor-owned gate stays silent: the run just times out
          (is (= :timeout (:reason (workflow/await! rt "await-executor-gate"
                                                    {:timeout-secs 1}))))
          (workflow/register-executor! :await-test-executor
                                       (fn [step]
                                         (when (= gate-id (:id step))
                                           {:why "test"})))
          (let [result (workflow/await! rt "await-executor-gate" {:timeout-secs 1})]
            (is (= :stalled (:reason result)))
            (is (= {:why "test"} (get-in result [:detail :stall])))))))))

(deftest await-explicit-runtime-arity-matches-ambient-result-for-a-completed-run
  (with-runtime
    (fn [rt _]
      (workflow/start! "await-explicit-runtime"
                       (workflow/workflow "Await explicit runtime" (workflow/step :do-it "Do it" :self))
                       {})
      (workflow/complete! "await-explicit-runtime")
      (let [ambient (workflow/await! "await-explicit-runtime" {:timeout-secs 1})
            explicit (workflow/await! rt "await-explicit-runtime" {:timeout-secs 1})]
        (is (= :done (:reason explicit)))
        (is (= ambient explicit))))))

(deftest await-rearms-once-for-an-accepted-weaver-restart
  (with-runtime
    (fn [rt _]
      (let [calls (atom [])]
        (let [result (with-redefs-fn {#'millhouse.workflow/attention
                                      (fn [_runtime run-id]
                                        (swap! calls conj run-id)
                                        (if (= 1 (count @calls))
                                          (throw (ex-info "peer Weaver restarted"
                                                          {:code :weaver/restarted}))
                                          {:reason :done :done true}))}
                       #(workflow/await! rt "await-restarted"
                                         {:timeout-secs 1 :poll-ms 1}))]
          (is (= :done (:reason result))))
        (is (= ["await-restarted" "await-restarted"] @calls))))))

(deftest await-rethrows-a-second-weaver-restart
  (with-runtime
    (fn [rt _]
      (let [calls (atom [])
            failure (with-redefs-fn {#'millhouse.workflow/attention
                                     (fn [_runtime run-id]
                                       (swap! calls conj run-id)
                                       (throw (ex-info "peer Weaver restarted"
                                                       {:code :weaver/restarted})))}
                      #(try
                         (workflow/await! rt "await-restarted-twice"
                                          {:timeout-secs 1 :poll-ms 1})
                         nil
                         (catch clojure.lang.ExceptionInfo e e)))]
        (is (= :weaver/restarted (:code (ex-data failure))))
        (is (= ["await-restarted-twice" "await-restarted-twice"] @calls))))))

(deftest await-restart-keeps-the-original-time-budget
  (with-runtime
    (fn [rt _]
      (test-alpha/set-clock! rt (test-alpha/manual-clock Instant/EPOCH))
      (let [calls (atom 0)
            result (with-redefs-fn {#'millhouse.workflow/attention
                                    (fn [_runtime _run-id]
                                      (swap! calls inc)
                                      (if (= 1 @calls)
                                        (do (test-alpha/advance! rt (Duration/ofMillis 900))
                                            (throw (ex-info "peer Weaver restarted"
                                                            {:code :weaver/restarted})))
                                        (do (test-alpha/advance! rt (Duration/ofMillis 200))
                                            {:reason :waiting})))}
                     #(workflow/await! rt "await-restart-budget"
                                       {:timeout-secs 1 :poll-ms 1}))]
        (is (= :timeout (:reason result)))
        (is (= 2 @calls))))))

(deftest await!-fails-loudly-for-malformed-timeout-secs-or-poll-ms
  (with-runtime
    (fn [rt _]
      (doseq [bad [-1 1.5 "1"]]
        (testing (str "timeout-secs " (pr-str bad))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #":timeout-secs must be a non-negative integer"
                                (workflow/await! rt "await-malformed-opts" {:timeout-secs bad}))))
        (testing (str "poll-ms " (pr-str bad))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #":poll-ms must be a positive integer"
                                (workflow/await! rt "await-malformed-opts" {:poll-ms bad})))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":poll-ms must be a positive integer"
                            (workflow/await! rt "await-malformed-opts" {:poll-ms 0}))))))

(s/def ::scope string?)

(s/def ::static-build-params (s/keys :req-un [::scope]))

(workflow/defworkflow static-build
  "Build an agreed scope."
  {:entrypoints #{:start :continue}
   :param-spec ::static-build-params
   :defaults {:reviewer "agent"}}
  (workflow/workflow
   (fn [{:keys [scope]}] (str "Build " scope))
   (workflow/step :implement
                  (fn [{:keys [scope reviewer]}] (str "Implement " scope " for " reviewer))
                  :self)))

(workflow/defworkflow static-review
  "Review a completed implementation."
  {:entrypoints #{:call}}
  (workflow/workflow
   "Review"
   (workflow/step :inspect "Inspect the change" :self)))

(deftest static-definition-start-merges-defaults-and-records-identity
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-build 'millhouse.workflow-runtime-test/static-build)
      (workflow/start! "static-run" :wt-build {:scope "compact queue"})
      (let [root (workflow/current-root "static-run")]
        (is (= "Build compact queue" (:title root)))
        (is (= "wt-build" (get-in root [:attributes :workflow/definition-name]))
            "the registered name is what a later revision resolves against")
        (is (= "millhouse.workflow-runtime-test/static-build"
               (get-in root [:attributes :workflow/definition]))
            "the resolved symbol records which definition this root was built from"))
      (is (= "Implement compact queue for agent" (:title (workflow/ready-step "static-run")))
          "declared :defaults merge under the caller's params"))))

(deftest static-definition-start-requires-the-start-entrypoint
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-review 'millhouse.workflow-runtime-test/static-review)
      (let [thrown (try (workflow/start! "no-start" :wt-review {})
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/entrypoint-unsupported (:reason (ex-data thrown))))
        (is (= :start (:entrypoint (ex-data thrown))))
        (is (= [:call] (:entrypoints (ex-data thrown)))))
      (is (nil? (workflow/current-root "no-start"))
          "the run is refused before anything is poured")
      ;; the registry is the capability boundary; trusted Clojure holding the
      ;; Var is already past it
      (workflow/start! "direct-var" #'static-review {})
      (is (= "Inspect the change" (:title (workflow/ready-step "direct-var")))))))

(deftest registered-name-routing-requires-the-continue-entrypoint
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-build 'millhouse.workflow-runtime-test/static-build)
      (workflow/register-workflow! :wt-review 'millhouse.workflow-runtime-test/static-review)
      ;; :wt-build declares :continue, so the authored route pours it — the
      ;; choice input carries the scope its :param-spec requires
      (workflow/start! "route-ok" (registry-router-stage {:target :wt-build}) {})
      (is (= ["Implement compact queue for agent"]
             (mapv :title (:ready (workflow/choose! "route-ok" :advance
                                                    {:scope "compact queue"})))))
      ;; :wt-review is call-only, so the same route is refused before mutation
      (workflow/start! "route-bad" (registry-router-stage {:target :wt-review}) {})
      (let [go-id (:id (workflow/ready-step "route-bad"))
            thrown (try (workflow/choose! "route-bad" :advance)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/entrypoint-unsupported (:reason (ex-data thrown))))
        (is (= :continue (:entrypoint (ex-data thrown))))
        (is (= "active" (:state (weaver/show rt go-id))))))))

(s/def ::reviewer string?)

(s/def ::spec-first-params (s/keys :req-un [::scope ::reviewer]))

(workflow/defworkflow spec-first-build
  "Build a scope under a whole-map param contract."
  {:entrypoints #{:start :continue}
   :param-spec ::spec-first-params
   :defaults {:reviewer "agent"}}
  (workflow/workflow
   (fn [{:keys [scope]}] (str "Build " scope))
   (workflow/step :implement
                  (fn [{:keys [scope reviewer]}] (str "Implement " scope " for " reviewer))
                  :self)))

(s/def ::approval-note string?)

(s/def ::approval-input (s/keys :req-un [::approval-note]))

(workflow/defworkflow spec-first-signoff
  "Approve or reject under a live checkpoint input spec."
  {:entrypoints #{:start}}
  (workflow/workflow
   "Sign off"
   (workflow/checkpoint :signoff "Approve the change"
                        :kind :agent
                        :choices [{:key :approve
                                   :label "Approve"
                                   :input {:spec ::approval-input
                                           :doc "Record why this was approved."}}
                                  {:key :reject :label "Reject"}])))

(workflow/defworkflow spec-first-revisable
  "Revise its own params under a whole-map contract."
  {:entrypoints #{:start}
   :param-spec ::spec-first-params
   :defaults {:reviewer "agent"}}
  (workflow/workflow
   (fn [{:keys [scope]}] (str "Revise " scope))
   (workflow/checkpoint :again "Revise or stop"
                        :kind :agent
                        :choices [{:key :bad :label "Bad" :revise {:params {:scope 42}}}
                                  {:key :good :label "Good"
                                   :revise {:params {:scope "second pass"}}}
                                  {:key :stop :label "Stop"}])))

(def ^:private static-input-definition
  (workflow/workflow
   "Static input"
   {:entrypoints #{:start}}
   (workflow/checkpoint :approve-step "Approve" :kind :agent
                        :choices [{:key :approve
                                   :input ::approval-input}])))

(defn- spec-first-router [{:keys [target]}]
  (workflow/workflow
   "Router"
   {:entrypoints #{:start}}
   (workflow/checkpoint :go "Go"
                        :kind :agent
                        :choices [{:key :advance :label "Advance" :next target}])))

(deftest spec-first-params-merge-defaults-before-whole-map-validation
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-spec-build 'millhouse.workflow-runtime-test/spec-first-build)
      ;; :reviewer is required by the spec and supplied only by :defaults, so a
      ;; start that omits it proves defaults merge before validation
      (workflow/start! "spec-ok" :wt-spec-build {:scope "compact queue"})
      (is (= "Implement compact queue for agent"
             (:title (workflow/ready-step "spec-ok"))))
      ;; the caller's own map compiles: validation never substitutes conform output
      (is (= {:scope "compact queue" :reviewer "agent"}
             (get-in (workflow/current-root "spec-ok") [:attributes :workflow/context]))))))

(deftest spec-first-params-fail-before-any-mutation
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-spec-build 'millhouse.workflow-runtime-test/spec-first-build)
      (testing "a missing required key fails with the contract and the violation"
        (let [thrown (try (workflow/start! "spec-missing" :wt-spec-build {})
                          (catch clojure.lang.ExceptionInfo e e))
              data (ex-data thrown)]
          (is (= :workflow/params-invalid (:reason data)))
          (is (= ::spec-first-params (:spec data)))
          (is (= :wt-spec-build (:name data)))
          (is (re-find #"scope" (:explain data)))
          (is (= "root" (get-in data [:spec-forms 0 "relation"])))
          ;; the failure carries the same named projection fields discovery
          ;; shows (DELTA-Spj-003.CC4)
          (is (= "map" (get-in data [:contract "kind"])))
          (is (contains? (:template data) "scope"))
          (is (nil? (workflow/current-root "spec-missing"))
              "nothing pours when params are rejected")))
      (testing "a wrong-typed value fails the same way"
        (let [thrown (try (workflow/start! "spec-typed" :wt-spec-build {:scope 42})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/params-invalid (:reason (ex-data thrown))))
          (is (nil? (workflow/current-root "spec-typed"))))))))

(deftest spec-first-params-guard-named-routes-and-revisions
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-spec-build 'millhouse.workflow-runtime-test/spec-first-build)
      (testing "a named route validates the target's merged params"
        (workflow/start! "route-invalid" (spec-first-router {:target :wt-spec-build}) {})
        (let [go-id (:id (workflow/ready-step "route-invalid"))
              thrown (try (workflow/choose! "route-invalid" :advance {:scope 42})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/params-invalid (:reason (ex-data thrown))))
          (is (= "active" (:state (weaver/show rt go-id)))
              "the checkpoint stays ready, so the run is resumable"))
        (is (= ["Implement compact queue for agent"]
               (mapv :title (:ready (workflow/choose! "route-invalid" :advance
                                                      {:scope "compact queue"})))))))))

(deftest spec-first-revision-validates-its-override-params
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-revisable 'millhouse.workflow-runtime-test/spec-first-revisable)
      (workflow/start! "revise-run" :wt-revisable {:scope "first pass"})
      (let [checkpoint-id (:id (workflow/ready-checkpoint "revise-run"))
            thrown (try (workflow/choose! "revise-run" :bad)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/params-invalid (:reason (ex-data thrown))))
        (is (= "active" (:state (weaver/show rt checkpoint-id)))
            "the run keeps its stage when the revision is rejected"))
      (workflow/choose! "revise-run" :good)
      (is (= "Revise second pass" (:title (workflow/current-root "revise-run"))))
      (is (:done (workflow/choose! "revise-run" :stop))))))

(deftest checkpoint-input-spec-is-recorded-at-pour-and-validated-live
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/start! "input-run" #'spec-first-signoff {})
      (testing "the poured checkpoint records identity, doc, and the form graph"
        (let [detail (workflow/choice-detail "input-run" :approve)
              declared (get detail "input-spec")]
          (is (= "millhouse.workflow-runtime-test/approval-input" (get declared "spec")))
          (is (= "Record why this was approved." (get declared "doc")))
          ;; entries may accrete "doc"/"private" var-metadata enrichment on top
          ;; of the stable spec/relation/form triple (millstrand.api.spec.alpha)
          (is (= [{"spec" "millhouse.workflow-runtime-test/approval-input"
                   "relation" "root"
                   "form" (pr-str (s/form ::approval-input))}
                  {"spec" "millhouse.workflow-runtime-test/approval-note"
                   "relation" "keyword-reference"
                   "form" (pr-str (s/form ::approval-note))}]
                 (mapv #(select-keys % ["spec" "relation" "form"])
                       (get declared "spec-forms"))))))
      (testing "invalid input fails before mutation with the current contract"
        (let [step-id (:id (workflow/ready-checkpoint "input-run"))
              thrown (try (workflow/choose! "input-run" :approve {})
                          (catch clojure.lang.ExceptionInfo e e))
              data (ex-data thrown)]
          (is (= :workflow/input-invalid (:reason data)))
          (is (= ::approval-input (:spec data)))
          (is (re-find #"approval-note" (:explain data)))
          (is (= "active" (:state (weaver/show rt step-id))))))
      (testing "a choice declaring no input contract takes any map"
        (is (:done (workflow/choose! "input-run" :reject {:anything "goes"}))))
      (testing "valid input records the choice"
        (workflow/start! "input-ok" #'spec-first-signoff {})
        (is (:done (workflow/choose! "input-ok" :approve {:approval-note "scope agreed"})))))))

(deftest workflow-choices-projects-live-input-contracts
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/start! "choices-run" #'spec-first-signoff {})
      (testing "a spec-first choice input carries the live projection"
        (let [result (workflow/run-choices {:run-id "choices-run"})
              declared (get-in result [:choices "approve" "input-spec"])]
          (is (= "workflow choices" (:operation result)))
          (is (= "choices-run" (:run-id result)))
          (is (true? (get declared "registered")))
          (is (= "map" (get-in declared ["contract" "kind"])))
          (is (contains? (get declared "template") "approval-note"))
          (is (= "root" (get-in declared ["spec-forms" 0 "relation"])))
          (is (nil? (get-in result [:choices "reject" "input-spec"]))
              "a choice with no declared input has no contract to project")))
      (testing "a stored spec that no longer resolves reports registered false"
        (try
          (s/def ::approval-input nil)
          (let [declared (get-in (workflow/run-choices {:run-id "choices-run"})
                                 [:choices "approve" "input-spec"])]
            (is (false? (get declared "registered")))
            (is (some? (get declared "spec-forms"))
                "the pour-time record stays readable"))
          (finally
            (s/def ::approval-input (s/keys :req-un [::approval-note]))))))))

(deftest checkpoint-input-spec-resolves-the-live-spec-not-the-recorded-form
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/start! "live-input" #'spec-first-signoff {})
      (try
        ;; redefining a nested spec changes validation while the outer form the
        ;; worker was shown is unchanged
        (s/def ::approval-note (s/and string? #(< 10 (count %))))
        (let [thrown (try (workflow/choose! "live-input" :approve {:approval-note "short"})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/input-invalid (:reason (ex-data thrown)))))
        (is (:done (workflow/choose! "live-input" :approve
                                     {:approval-note "long enough to pass"})))
        (finally (s/def ::approval-note string?))))))

(deftest checkpoint-input-spec-removal-fails-loudly
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/start! "gone-input" #'spec-first-signoff {})
      (try
        (s/def ::approval-input nil)
        (let [step-id (:id (workflow/ready-checkpoint "gone-input"))
              thrown (try (workflow/choose! "gone-input" :approve {:approval-note "x"})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/input-spec-missing (:reason (ex-data thrown))))
          (is (= "active" (:state (weaver/show rt step-id)))))
        (finally (s/def ::approval-input (s/keys :req-un [::approval-note])))))))

(deftest static-choice-input-requires-its-whole-map-spec
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/start! "static-input" static-input-definition {})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Value does not satisfy the named spec"
                            (workflow/choose! "static-input" :approve {})))
      (is (:done (workflow/choose! "static-input" :approve {:approval-note "fine"}))))))

(deftest param-spec-removed-after-registration-fails-live
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-spec-build 'millhouse.workflow-runtime-test/spec-first-build)
      (try
        (s/def ::spec-first-params nil)
        (let [thrown (try (workflow/start! "spec-gone" :wt-spec-build {:scope "queue"})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/param-spec-missing (:reason (ex-data thrown))))
          (is (nil? (workflow/current-root "spec-gone"))))
        (finally (s/def ::spec-first-params (s/keys :req-un [::scope ::reviewer])))))))

(def ^:private spec-first-caller
  (workflow/workflow
   "Caller"
   {:entrypoints #{:start}}
   (workflow/call :build :wt-callable {:scope "called scope"})))

(def ^:private spec-first-bad-caller
  (workflow/workflow
   "Bad caller"
   {:entrypoints #{:start}}
   (workflow/call :build :wt-callable {:scope 42})))

(workflow/defworkflow spec-first-callable
  "A call target under a whole-map param contract."
  {:entrypoints #{:call}
   :param-spec ::spec-first-params
   :defaults {:reviewer "agent"}}
  (workflow/workflow
   "Callable"
   (workflow/step :implement
                  (fn [{:keys [scope reviewer]}] (str "Implement " scope " for " reviewer))
                  :self)))

(deftest registered-call-targets-validate-their-params
  ;; a call target reached by registered name meets the same contract boundary
  ;; that requires its :call entrypoint
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-callable 'millhouse.workflow-runtime-test/spec-first-callable)
      (workflow/start! "call-ok" #'spec-first-caller {})
      (is (= "Implement called scope for agent"
             (:title (workflow/ready-step "call-ok"))))
      (let [thrown (try (workflow/start! "call-bad" #'spec-first-bad-caller {})
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/params-invalid (:reason (ex-data thrown))))
        (is (nil? (workflow/current-root "call-bad"))
            "the caller's own run pours nothing when the target rejects its params")))))

(s/def ::feature string?)
