(ns millhouse.spools.auto-review-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [millhouse.spools.auto-review :as review]
            [millhouse.spools.auto-review.internal.board :as board]
            [millhouse.spools.auto-review.internal.comments :as comments]
            [millhouse.spools.auto-review.internal.io :as review-io]
            [millhouse.spools.auto-review.internal.logs :as logs]
            [millhouse.spools.auto-review.internal.publication :as publication]
            [millhouse.spools.auto-review.internal.views :as views]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.api.batch.alpha :as batch]))

(def config (review/validate-config
             {:repo-dir "/tmp/source" :reviewers ["correctness" "tests"]
              :setup "prepare-review-workspace" :teardown "release-review-workspace"}))

(deftest explicit-activation-and-revision-identity
  (is (false? (:poll? (review/validate-config config))))
  (doseq [bad [(assoc config :reviewers [])
               (assoc config :worktree-root "/tmp/reviews")
               (assoc config :max-active-reviews 0)
               (assoc config :poll? "true")
               (assoc config :setup " ")
               (assoc config :setup ["yarn" "install"])
               (assoc config :teardown " ")
               (assoc config :max-diff-bytes 10)
               (assoc config :setup-timeout-seconds 0)
               (assoc config :teardown-timeout-seconds 0)
               (assoc config :typo true)]]
    (is (thrown? Exception (review/validate-config bad))))
  (is (= 240 (:teardown-timeout-seconds config)))
  (is (not= (board/key-for config {:project_id 1 :iid 2 :sha "a"})
            (board/key-for config {:project_id 1 :iid 2 :sha "b"}))))

(deftest review-finish-keeps-the-invocation-open-for-its-bounded-teardown
  (let [declaration (:millstrand.api.authoring.alpha/declaration (meta #'review/review))
        finish-spec (get-in declaration [:entry :arg-spec :subcommands "finish"])]
    (is (= :unbounded (:deadline-class finish-spec)))
    (is (= 240 (:teardown-timeout-seconds config)))))

(defn- controlled-executor [await-termination]
  (proxy [java.util.concurrent.AbstractExecutorService] []
    (shutdown [])
    (shutdownNow [] [])
    (isShutdown [] true)
    (isTerminated [] false)
    (awaitTermination [_ _] (await-termination))
    (execute [_] (throw (Exception. "Worker must not accept work while closing")))))

(deftest concurrent-close-retains-worker-ownership-and-disarms-wakes
  (let [awaiting (promise)
        terminate (promise)
        executor (controlled-executor #(do (deliver awaiting true) @terminate))
        st (atom {:config (assoc config :poll? true) :executor executor
                  :closing? false :pending #{:poll} :busy true})
        scheduled (atom [])]
    (with-redefs [runtime/spool-state (fn [& _] {:state st})
                  scheduler/pending (constantly [])
                  scheduler/schedule! (fn [& args] (swap! scheduled conj args))]
      (let [closing (future (review/close! {:runtime :runtime}))]
        @awaiting
        (is (:closing? @st))
        (is (identical? executor (:executor @st)))
        (is (empty? (:pending @st)))
        (is (thrown-with-msg? Exception #"Only one review resource"
                              (review/open! {:runtime :runtime} config)))
        (is (thrown-with-msg? Exception #"closing"
                              (review/request! :runtime :poll)))
        (review/wake! {:runtime :runtime})
        (review/prune-wake! {:runtime :runtime})
        (is (empty? @scheduled))
        (deliver terminate true)
        (is (= {:closed :mr-review} @closing))
        (is (nil? (:executor @st)))
        (is (false? (:closing? @st)))))))

(deftest failed-worker-termination-prevents-reopen-until-close-retry-succeeds
  (let [terminate? (atom false)
        executor (controlled-executor #(boolean @terminate?))
        st (atom {:config config :executor executor :closing? false
                  :pending #{:recover} :busy true})]
    (with-redefs [runtime/spool-state (fn [& _] {:state st})
                  scheduler/pending (constantly [])]
      (is (thrown-with-msg? Exception #"did not stop"
                            (review/close! {:runtime :runtime})))
      (is (:closing? @st))
      (is (identical? executor (:executor @st)))
      (is (thrown-with-msg? Exception #"Only one review resource"
                            (review/open! {:runtime :runtime} config)))
      (reset! terminate? true)
      (is (= {:closed :mr-review} (review/close! {:runtime :runtime})))
      (is (nil? (:executor @st))))))

(deftest gitlab-pagination-and-revision-race
  (let [calls (atom [])]
    (with-redefs [review-io/command!
                  (fn [_ argv _ _]
                    (swap! calls conj argv)
                    (if (= "1" (last argv))
                      (str "[" (str/join "," (repeat 100 "{}")) "]")
                      "[]"))]
      (is (= 100 (count (review-io/open-mrs (assoc config :glab-bin "glab")))))
      (is (= ["1" "2"] (mapv last @calls))))
    (with-redefs [review-io/command! (fn [& _] "{\"state\":\"opened\",\"sha\":\"moved\"}")]
      (is (thrown-with-msg? Exception #"MR moved"
                            (review-io/revision config {:iid 1 :sha "old"}))))
    (with-redefs [review-io/command! (fn [& _] "{\"state\":\"merged\"}")]
      (is (= "merged" (review-io/mr-state config 1))))
    (with-redefs [review-io/command! (fn [& _] "{\"state\":\"locked\"}")]
      (is (thrown-with-msg? Exception #"unknown MR state"
                            (review-io/mr-state config 1))))))

(deftest failed-or-unsettled-review-is-never-success
  (doseq [run [{:status "failed" :settled true :error "provider failed"}
               {:status "stopped" :substatus "requested" :settled true}
               {:status "stopped" :substatus "completed" :settled false :result "No findings"}
               {:status "stopped" :substatus "completed" :settled true :result ""}]]
    (let [outcome (atom nil)
          card {:id "card" :attributes {:mr-review/stage "running"
                                        :mr-review/runs "[{:id \"run\" :reviewer \"tests\"}]"
                                        :mr-review/mr "{:iid 1 :sha \"head\"}"}}]
      (with-redefs [logs/append! (fn [& _])
                    board/cards (constantly [card])
                    weaver/op! (fn [& _] run)
                    board/finish! (fn [_ _ status settlement]
                                    (reset! outcome [status (:report settlement)]))]
        (review/settle! :runtime)
        (is (= "failed" (first @outcome)))
        (is (str/includes? (second @outcome) "tests"))))))

(deftest unchanged-revisions-and-drafts-do-not-launch
  (let [mr {:project_id 1 :iid 2 :sha "old" :state "opened" :draft false}
        card {:id "seen" :attributes {:mr-review/review "true" :mr-review/stage "reviewed"
                                      :mr-review/repo (:repo-dir config)
                                      :mr-review/current "true"
                                      :mr-review/key (board/key-for config mr)}}]
    (with-redefs [logs/append! (fn [& _])
                  board/cards (constantly [card])
                  review-io/open-mrs (constantly [mr (assoc mr :iid 3 :draft true)])
                  weaver/op! (fn [& _] (throw (Exception. "Should not dispatch")))]
      (is (= {:open 2 :selected 0}
             (review/poll-once! :runtime (review/validate-config config)))))))

(deftest awaiting-human-decisions-reserve-both-slots
  (let [cards (mapv (fn [id]
                      {:id id :state "active"
                       :attributes {:mr-review/stage "reviewed" :mr-review/current "false"
                                    :mr-review/repo (:repo-dir config)
                                    :mr-review/key (str "previous-" id)
                                    :mr-review/mr (pr-str {:iid ({"a" 1 "b" 2} id)})}}) ["a" "b"])]
    (with-redefs [logs/append! (fn [& _])
                  board/cards (constantly cards)
                  review-io/open-mrs (constantly [{:iid 3 :project_id 1 :sha "new"
                                                   :state "opened" :draft false}])
                  review-io/authenticated-user-id (constantly 42)
                  review-io/mr-state (fn [& _] "opened")
                  weaver/op! (fn [& _] (throw (Exception. "Both human-decision slots are occupied")))]
      (is (= {:open 1 :selected 0} (review/poll-once! :runtime config))))))

(deftest requested-reviews-have-priority-and-do-not-use-ordinary-capacity
  (let [card (fn [id iid reviewers]
               {:id id :state "active"
                :attributes {:mr-review/stage "running"
                             :mr-review/repo (:repo-dir config)
                             :mr-review/current "true"
                             :mr-review/key (str "previous-" id)
                             :mr-review/runs "[]"
                             :mr-review/mr (pr-str {:iid iid :reviewers reviewers})}})
        ordinary-cards [(card "ordinary-1" 1 []) (card "ordinary-2" 2 [])]
        requested-card (card "requested" 3 [{:id 42}])
        requested {:iid 3 :project_id 1 :sha "requested" :state "opened" :draft false
                   :reviewers [{:id 42}]}
        ordinary {:iid 4 :project_id 1 :sha "ordinary" :state "opened" :draft false
                  :reviewers []}
        launched (atom [])
        run-poll
        (fn [cards candidates max-active]
          (reset! launched [])
          (with-redefs-fn
            {#'review/roster (fn [& _] [])
             #'review/start-review! (fn [_ _ mr _] (swap! launched conj (:iid mr)))}
            #(with-redefs [logs/append! (fn [& _])
                           board/cards (constantly cards)
                           board/patch! (fn [_ review _] review)
                           review-io/open-mrs (constantly candidates)
                           review-io/authenticated-user-id (constantly 42)
                           review-io/revision (fn [_ mr] mr)]
               (review/poll-once! :runtime
                                  (assoc config :max-active-reviews max-active)))))]
    (is (= {:open 2 :selected 1}
           (run-poll ordinary-cards [ordinary requested] 2)))
    (is (= [3] @launched))
    (is (= {:open 1 :selected 1}
           (run-poll [requested-card] [ordinary] 1)))
    (is (= [4] @launched))))

(deftest terminal-mrs-are-finished-before-capacity-is-filled
  (let [cards
        (atom
         (mapv (fn [[id iid stage]]
                 {:id id :state "active"
                  :attributes {:mr-review/stage stage :mr-review/current "true"
                               :mr-review/decision "pending"
                               :mr-review/repo (:repo-dir config)
                               :mr-review/key (str "previous-" id)
                               :mr-review/mr (pr-str {:iid iid :sha (str "head-" id)})}})
               [["merged-review" 1 "reviewed"]
                ["merged-old-review" 1 "failed"]
                ["closed-review" 2 "failed"]]))
        candidate {:iid 3 :project_id 1 :sha "new" :state "opened" :draft false}
        decisions (atom [])
        teardowns (atom [])
        state-reads (atom [])
        revision-reads (atom [])]
    (with-redefs [logs/append! (fn [& _])
                  board/cards (fn [& _] @cards)
                  board/patch! (fn [_ review _] review)
                  board/note! (fn [& _])
                  runtime/now (fn [_] (java.time.Instant/parse "2026-09-15T00:00:00Z"))
                  review-io/open-mrs (constantly [candidate])
                  review-io/authenticated-user-id (constantly 42)
                  review-io/mr-state (fn [_ iid]
                                       (swap! state-reads conj iid)
                                       ({1 "merged" 2 "closed"} iid))
                  review-io/teardown! (fn [_ review _ _]
                                        (swap! teardowns conj (:id review))
                                        {:shell "/bin/bash" :log "/tmp/teardown.log"})
                  review-io/revision (fn [_ mr]
                                       (swap! revision-reads conj (:iid mr))
                                       nil)
                  views/decide! (fn [_ id outcome by note before-close]
                                  (let [review (first (filter #(= id (:id %)) @cards))]
                                    (before-close review)
                                    (swap! cards (fn [reviews]
                                                   (mapv #(if (= id (:id %))
                                                            (assoc % :state "closed") %)
                                                         reviews)))
                                    (swap! decisions conj [id outcome by note])))]
      (is (= {:open 1 :selected 0} (review/poll-once! :runtime config)))
      (is (= ["merged-review" "merged-old-review" "closed-review"] @teardowns))
      (is (= [["merged-review" "done" "mr-review"
               "GitLab MR !1 was merged; finished the local review automatically."]
              ["merged-old-review" "done" "mr-review"
               "GitLab MR !1 was merged; finished the local review automatically."]
              ["closed-review" "done" "mr-review"
               "GitLab MR !2 was closed; finished the local review automatically."]]
             @decisions))
      (is (= [1 2] @state-reads))
      (is (= [3] @revision-reads)))))

(deftest latest-pipeline-aggregate-controls-admission
  (let [sha (clojure.string/join (repeat 40 "a"))
        mr {:iid 1 :sha sha :state "opened" :draft false
            :diff_refs {:base_sha sha :start_sha sha :head_sha sha}
            ;; An older green pipeline must not excuse a non-green head.
            :pipeline {:id 9 :status "success" :sha sha}}
        read-revision (fn [detail]
                        (with-redefs [review-io/command! (fn [& _] (json/write-str detail))]
                          (review-io/revision config mr)))]
    (doseq [status [nil "created" "pending" "running" "failed" "canceled" "skipped" "manual" "unknown"]]
      (is (nil? (read-revision (assoc mr :head_pipeline {:id 10 :sha sha :status status}))))
      (is (not (review-io/pipeline-success? (assoc mr :head_pipeline {:id 10 :sha sha :status status})))))
    (is (nil? (read-revision mr)))
    (is (nil? (read-revision (assoc mr :head_pipeline {:id 10 :sha "old" :status "success"}))))
    (let [passing (assoc mr :head_pipeline {:id 10 :sha sha :status "success"
                                            :detailed_status {:group "success-with-warnings"}
                                            :jobs [{:status "failed" :allow_failure true}]})]
      (is (= passing (read-revision passing)))
      (is (review-io/pipeline-success? passing)))))

(deftest waiting-ci-does-not-starve-passing-mrs-or-consume-capacity
  (let [calls (atom []) candidates [{:iid 1} {:iid 2} {:iid 3}]]
    (with-redefs [review-io/revision (fn [_ mr]
                                       (swap! calls conj (:iid mr))
                                       (when-not (= 1 (:iid mr)) mr))]
      (is (= [] (review/passing-revisions config candidates 0)))
      (is (empty? @calls))
      (is (= [{:iid 2}] (review/passing-revisions config candidates 1)))
      (is (= [1 2] @calls)))))

(deftest decisions-distinguish-observed-ci-from-capacity
  (let [events (atom [])
        sha (clojure.string/join (repeat 40 "a"))
        mr {:iid 1 :sha sha :state "opened"
            :diff_refs {:base_sha sha :start_sha sha :head_sha sha}
            :head_pipeline {:id 10 :sha sha :status "running"}}
        observe #(swap! events conj [%1 %2])]
    (with-redefs [review-io/command! (fn [& _] (json/write-str mr))]
      (is (empty? (review/passing-revisions config [mr] 1 observe)))
      (is (= "observed" (get-in @events [0 1 :ci])))
      (is (= "running" (get-in @events [0 1 :pipeline-status])))
      (is (= "pipeline-not-passing" (get-in @events [0 1 :reason]))))
    (reset! events [])
    (with-redefs [review-io/command! (fn [& _] (throw (Exception. "Should not read CI")))]
      (is (empty? (review/passing-revisions config [mr] 0 observe)))
      (is (= {:decision "skip" :reason "capacity" :ci "unknown"} (second (first @events)))))))

(deftest log-error-details-do-not-leak-payloads
  (is (= {:reason "operation-failed"}
         (logs/error-facts (ex-info "token-secret in stderr" {:stderr "secret"}))))
  (is (= {:reason "command-failed"}
         (logs/error-facts (ex-info "Command failed" {:stderr "secret"})))))

(deftest unavailable-log-storage-does-not-break-polling
  (let [states (atom {})]
    (with-redefs [runtime/spool-state (fn [_ key _ init]
                                        (or (@states key) (get (swap! states assoc key (init)) key)))
                  runtime/now (fn [_] (java.time.Instant/parse "2026-09-14T00:00:00Z"))
                  weaver/list (fn [& _] [{:id "log-root"}])
                  batch/apply! (fn [& _] (throw (ex-info "database secret" {:secret "token"})))
                  board/cards (constantly [])
                  review-io/open-mrs (constantly [])]
      (logs/configure! :runtime config)
      (is (= {:open 0 :selected 0} (review/poll-once! :runtime config)))
      (is (= "operation-failed" (get-in (logs/status :runtime) [:last-error :reason])))
      (is (not (str/includes? (pr-str (logs/status :runtime)) "secret"))))))

(deftest closed-review-hook-logs-are-pruned-at-the-configured-retention-boundary
  (let [log-file (java.nio.file.Files/createTempFile
                  "millstrand-review-review-setup-" ".log"
                  (make-array java.nio.file.attribute.FileAttribute 0))
        settings (atom config)
        decided-at "2026-09-01T00:00:00Z"
        review {:id "review" :attributes {:mr-review/review "true"
                                          :mr-review/repo (:repo-dir config)
                                          :mr-review/decided-at decided-at
                                          :mr-review/setup-log (str log-file)}}]
    (with-redefs [runtime/spool-state (fn [& _] {:config settings})
                  runtime/now (fn [_] (java.time.Instant/parse "2026-09-15T00:00:00Z"))
                  weaver/list (fn [_ query _]
                                (if (str/includes? (pr-str query) "mr-review/review")
                                  [review] []))
                  logs/append! (fn [& _] nil)]
      (is (= {:burned 0 :hook-logs 1} (logs/prune! :runtime)))
      (is (not (java.nio.file.Files/exists log-file (make-array java.nio.file.LinkOption 0)))))))

(deftest log-ordering-compares-instants-not-their-text
  (let [settings (atom nil)
        rows (mapv (fn [id at] {:id id :attributes {:mr-review/log-at at
                                                    :mr-review/log-json (json/write-str {:at at})}})
                   ["third" "first" "second"]
                   ["2026-09-14T00:00:00.100001Z" "2026-09-14T00:00:00Z" "2026-09-14T00:00:00.100Z"])]
    (with-redefs [runtime/spool-state (fn [& _] {:config settings})
                  weaver/list (fn [& _] rows)]
      (logs/configure! :runtime config)
      (is (= ["first" "second" "third"] (mapv :id (logs/entries :runtime {}))))
      (is (= ["second" "third"] (mapv :id (logs/entries :runtime {:limit 2})))))))

(deftest cleanup-failure-does-not-discard-queued-poll
  (let [st (atom {:pending #{:prune :poll} :busy true :config config})
        polled (atom false)]
    (with-redefs [runtime/spool-state (fn [& _] {:state st})
                  runtime/now (fn [_] (java.time.Instant/now))
                  logs/append! (fn [& _])
                  logs/prune! (fn [& _] (throw (Exception. "cleanup failed")))
                  review/poll-once! (fn [& _] (reset! polled true) {:open 0 :selected 0})]
      (#'review/drain! :runtime)
      (is @polled)
      (is (false? (:busy @st)))
      (is (= {:reason "operation-failed"} (:last-prune-error @st))))))

(deftest local-decision-guards-run-before-teardown
  (doseq [[stage decision outcome message]
          [["running" "pending" "done" #"still executing"]
           ["reviewed" "done" "dismissed" #"different decision"]
           ["reviewed" "pending" "approved" #"done or dismissed"]]]
    (with-redefs [runtime/spool-state (fn [& _] {:lock (Object.)})
                  views/require-review (fn [& _] {:id "review" :state "active"
                                                  :attributes {:mr-review/stage stage :mr-review/decision decision}})
                  batch/apply! (fn [& _] (throw (Exception. "Must not mutate")))]
      (is (thrown-with-msg? Exception message
                            (views/decide! :rt "review" outcome nil nil
                                           (fn [_] (throw (Exception. "Must not tear down")))))))))

(deftest teardown-completes-before-close-and-failure-leaves-the-decision-unmutated
  (let [review {:id "review" :state "active"
                :attributes {:mr-review/review "true" :mr-review/stage "reviewed"
                             :mr-review/decision "pending"
                             :mr-review/mr "{:iid 7 :sha \"head\" :diff_refs {:base_sha \"base\"}}"
                             :mr-review/workspace "{:kind \"ready\" :worktree_path \"/tmp/review\"}"
                             :worktree "/tmp/review"}}
        patches (atom []) notes (atom [])]
    (with-redefs [board/patch! (fn [_ _ attrs] (swap! patches conj attrs))
                  board/note! (fn [_ _ text] (swap! notes conj text))
                  logs/append! (fn [& _])
                  runtime/now (fn [_] (java.time.Instant/parse "2026-09-15T00:00:00Z"))
                  review-io/teardown! (fn [_ _ _ workspace]
                                        (is (= "/tmp/review" (:worktree workspace)))
                                        {:shell "/bin/bash" :log "/tmp/teardown.log"})]
      (#'review/teardown-review! :rt config review)
      (is (= ["running" "done"] (mapv :mr-review/teardown-status @patches)))
      (is (some #(str/includes? % "teardown completed") @notes)))
    (with-redefs [review-io/teardown! (fn [& _] (throw (Exception. "Must not rerun")))]
      (is (nil? (#'review/teardown-review!
                 :rt config (assoc-in review [:attributes :mr-review/teardown-status] "done")))))
    (reset! patches [])
    (with-redefs [board/patch! (fn [_ _ attrs] (swap! patches conj attrs))
                  board/note! (fn [& _])
                  logs/append! (fn [& _])
                  review-io/teardown! (fn [& _] (throw (ex-info "cleanup broke" {:log "/tmp/fail.log"})))]
      (is (thrown-with-msg? Exception #"review remains open"
                            (#'review/teardown-review! :rt config review)))
      (is (= ["running" "failed"] (mapv :mr-review/teardown-status @patches))))
    (let [events (atom [])]
      (with-redefs [runtime/spool-state (fn [& _] {:lock (Object.)})
                    views/require-review (fn [& _] review)
                    views/show-review (fn [& _] {:review :closed})
                    runtime/now (fn [_] (java.time.Instant/parse "2026-09-15T00:00:00Z"))
                    batch/apply! (fn [& _] (swap! events conj :closed))]
        (views/decide! :rt "review" "done" nil nil (fn [_] (swap! events conj :teardown)))
        (is (= [:teardown :closed] @events)))
      (reset! events [])
      (with-redefs [runtime/spool-state (fn [& _] {:lock (Object.)})
                    views/require-review (fn [& _] review)
                    batch/apply! (fn [& _] (swap! events conj :closed))]
        (is (thrown-with-msg? Exception #"teardown broke"
                              (views/decide! :rt "review" "done" nil nil
                                             (fn [_] (throw (Exception. "teardown broke"))))))
        (is (empty? @events))))))

(deftest local-decision-holds-the-review-mutation-lock-through-teardown
  (let [lock (Object.)
        review {:id "review" :state "active"
                :attributes {:mr-review/review "true" :mr-review/stage "reviewed"
                             :mr-review/decision "pending"}}
        held? (atom false)]
    (with-redefs [runtime/spool-state (fn [& _] {:lock lock})
                  runtime/now (fn [_] (java.time.Instant/parse "2026-09-15T00:00:00Z"))
                  views/require-review (fn [& _] review)
                  views/show-review (fn [& _] {:review :closed})
                  batch/apply! (fn [& _] nil)]
      (views/decide! :rt "review" "done" nil nil
                     (fn [_] (reset! held? (Thread/holdsLock lock))))
      (is @held?))))

(deftest reference-only-prompt-and-real-dispatch-cwd
  (let [change {:cwd "/tmp/worktree" :head "head-sha" :base "base-sha"
                :diff (clojure.string/join (repeat 1000000 "GENERATED"))}
        plan (#'review/plan {:name "tests" :selected-seat "seat" :prompt "Check relevant tests."}
                            {:web_url "https://example.invalid/mr/1"} change)
        request (atom nil)]
    (is (< (count (:prompt plan)) 1500))
    (is (str/includes? (:prompt plan) "base-sha head-sha"))
    (is (str/includes? (:prompt plan) "setup completed"))
    (is (not (str/includes? (:prompt plan) "GENERATED")))
    (with-redefs [logs/append! (fn [& _])
                  board/record-runs! (fn [& _])
                  board/note! (fn [& _])
                  weaver/op! (fn [_ _ argv payloads] (reset! request [argv payloads]) {:id "run"})]
      (review/dispatch! :rt {:id "card" :attributes {:mr-review/plans [(assoc plan :target "pass")]}})
      (is (= ["run" "seat" "--cwd" "/tmp/worktree" "--target" "pass"] (take 6 (first @request))))
      (is (= (:prompt plan) (get-in @request [1 :payloads "prompt"]))))))

(deftest workspace-hooks-use-file-contract-load-shell-environment-and-enforce-deadlines
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "review-setup-" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo (io/file root "source")
        directory (io/file root "review-worktree")
        shell (io/file root "shell")
        tool (io/file root "fixture-tool")
        review {:id "review-1"}
        mr {:iid 7 :sha "head" :diff_refs {:base_sha "base"}}
        hook-config (assoc config :repo-dir (str repo))]
    (try
      (.mkdir repo)
      (spit shell (str "#!/bin/bash\nset -e\n[[ $1 == -lic ]]\n"
                       "export PATH=\"$(dirname \"$0\"):$PATH\"\ncd /\nexec /bin/bash -c \"$2\"\n"))
      (spit tool "#!/bin/bash\nprintf '%s' \"$PWD\" > ready\nprintf 'install output\\n'\n")
      (.setExecutable shell true)
      (.setExecutable tool true)
      (with-redefs-fn {#'review-io/login-shell (constantly (str shell))}
        (fn []
          (let [script (str "mkdir -p \"$MILLSTRAND_REVIEW_REPO/../review-worktree\"\n"
                            "fixture-tool\nprintf 'second line\\n'\n"
                            "printf '{\"kind\":\"ready\",\"worktree_path\":\"%s\",\"token\":\"kept\"}\\n' "
                            "\"$MILLSTRAND_REVIEW_REPO/../review-worktree\" > \"$MILLSTRAND_REVIEW_RESULT\"")
                result (review-io/setup! (assoc hook-config :setup script) review mr)]
            (is (= (str repo) (slurp (io/file repo "ready"))))
            (is (= (.getCanonicalPath directory) (:worktree result)))
            (is (= "kept" (get-in result [:context :token])))
            (is (str/includes? (slurp (:log result)) "second line"))
            (review-io/teardown!
             (assoc hook-config :teardown
                    (str "cat \"$MILLSTRAND_REVIEW_WORKSPACE\" > ../workspace.json\n"
                         "printf '%s\\n' \"$MILLSTRAND_REVIEW_WORKTREE\" > ../torn-down"))
             review mr result)
            (is (= (.getCanonicalPath directory)
                   (str/trim (slurp (io/file root "torn-down")))))
            (is (= "kept" (:token (json/read-str (slurp (io/file root "workspace.json"))
                                                 :key-fn keyword)))))
          (is (thrown-with-msg? Exception #"invalid JSON"
                                (review-io/setup! (assoc hook-config :setup "true") review mr)))
          (is (thrown-with-msg? Exception #"setup failed"
                                (review-io/setup! (assoc hook-config :setup "false\ntouch should-not-exist")
                                                  review mr)))
          (is (not (.exists (io/file repo "should-not-exist"))))
          (is (thrown-with-msg? Exception #"setup timed out"
                                (review-io/setup! (assoc hook-config :setup "sleep 30"
                                                         :setup-timeout-seconds 1)
                                                  review mr)))))
      (finally
        (doseq [file (reverse (file-seq root))] (io/delete-file file))))))

(deftest workspace-inspection-rejects-tracked-changes-outside-the-admitted-revision
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "review-inspection-" (make-array java.nio.file.attribute.FileAttribute 0)))
        sha (clojure.string/join (repeat 40 "a"))
        mr {:sha sha :diff_refs {:base_sha sha}}]
    (try
      (with-redefs [review-io/command!
                    (fn [_ argv _ _]
                      (case (last argv)
                        "HEAD" sha
                        "--show-toplevel" (.getCanonicalPath directory)
                        "--untracked-files=no" " M app.clj\n"
                        (throw (ex-info "Unexpected Git invocation" {:argv argv}))))]
        (is (thrown-with-msg? Exception #"tracked changes outside the admitted revision"
                              (review-io/inspect-workspace! config mr (.getCanonicalPath directory)))))
      (finally
        (io/delete-file directory true)))))

(deftest failed-setup-publishes-no-reviewer-requests
  (let [events (atom []) card {:id "card"}]
    (with-redefs [board/claim! (fn [& _] card)
                  board/patch! (fn [& _])
                  board/note! (fn [& _])
                  logs/append! (fn [& _])
                  weaver/show (fn [& _] card)
                  review-io/setup! (fn [& _] (swap! events conj :setup) (throw (ex-info "setup failed" {})))
                  review-io/inspect-workspace! (fn [& _] (swap! events conj :inspect) {:changed? true})
                  board/prepare-dispatch! (fn [& _] (swap! events conj :dispatch))
                  board/finish! (fn [_ _ outcome _] (swap! events conj outcome))]
      (#'review/start-review! :rt (assoc config :setup "false")
                              {:iid 1 :sha "head" :diff_refs {:base_sha "base"}} [])
      (is (= [:setup "failed"] @events)))))

(def structured-result
  (json/write-str
   {:summary "Two actionable findings."
    :comments
    [{:title "New-line issue" :text "Fix the new-line behavior." :severity "P2"
      :position {:kind "line" :oldPath "src/a.clj" :newPath "src/a.clj"
                 :side "new" :line 3}}
     {:title "Overview issue" :text "Clarify the migration plan."
      :position {:kind "general" :reason "Applies to the whole MR."}}]}))

(deftest structured-reviewer-results-are-the-authoritative-source
  (let [parsed (comments/parse-result "correctness" "run-1" structured-result)]
    (is (= "Two actionable findings." (:summary parsed)))
    (is (= ["correctness" "correctness"] (mapv :category (:comments parsed))))
    (is (= ["run-1" "run-1"] (mapv :runId (:comments parsed))))
    (is (= "line" (get-in parsed [:comments 0 :position :kind])))
    (is (= "general" (get-in parsed [:comments 1 :position :kind]))))
  (doseq [invalid ["not json"
                   "```json\n{\"summary\":\"x\",\"comments\":[]}\n```"
                   "{\"summary\":\"x\",\"comments\":{},\"extra\":true}"]]
    (is (thrown? Exception (comments/parse-result "correctness" "run-1" invalid)))))

(deftest position-union-accepts-both-sides-ranges-and-explicit-nonline-arms
  (doseq [side ["old" "new"]]
    (is (= side (:side (comments/normalize-position
                        {:kind "line" :oldPath "a" :newPath "a"
                         :side side :line 4}))))
    (is (= 2 (:startLine (comments/normalize-position
                          {:kind "line" :oldPath "a" :newPath "a"
                           :side side :line 4 :startSide side :startLine 2})))))
  (is (thrown-with-msg? Exception #"side must be old or new"
                        (comments/normalize-position
                         {:kind "line" :oldPath "a" :newPath "a"
                          :side "context" :line 4})))
  (is (= {:kind "general" :reason "whole MR"}
         (comments/normalize-position {:kind "general" :reason "whole MR"})))
  (is (= {:kind "unsupported" :reason "binary file"}
         (comments/normalize-position {:kind "unsupported" :reason "binary file"})))
  (let [diffs [{:old_path "a" :new_path "a"
                :diff "@@ -1,2 +1,3 @@\n-old one\n-old two\n+new one\n+new two\n+new three"}]]
    (is (comments/position-valid? diffs
                                  {:kind "line" :oldPath "a" :newPath "a"
                                   :side "old" :line 2 :startSide "old" :startLine 1}))
    (is (comments/position-valid? diffs
                                  {:kind "line" :oldPath "a" :newPath "a"
                                   :side "new" :line 3 :startSide "new" :startLine 1}))
    (is (not (comments/position-valid? diffs
                                       {:kind "line" :oldPath "a" :newPath "a"
                                        :side "new" :line 4})))
    (is (comments/position-valid? diffs {:kind "general" :reason "whole MR"}))
    (is (not (comments/position-valid? diffs
                                       {:kind "unsupported" :reason "binary"})))))

(deftest settlement-batch-preserves-first-class-comments-and-derived-report
  (let [mr {:project_id 7 :iid 9 :sha "head"
            :web_url "https://example.invalid/mr/9"
            :diff_refs {:base_sha "base" :start_sha "start" :head_sha "head"}}
        review {:id "review" :attributes {:mr-review/stage "running"
                                          :mr-review/mr (pr-str mr)}}
        pass {:id "pass" :attributes {:mr-review/pass "true"
                                      :mr-review/reviewer "correctness"}}
        settlement (comments/settlement mr
                                        [{:id "run-1" :reviewer "correctness"
                                          :status "stopped" :substatus "completed"
                                          :result structured-result}])
        payload (atom nil)]
    (with-redefs [weaver/show (fn [_ _] review)
                  board/children (fn [& _] [pass])
                  board/report-note (fn [& _] nil)
                  runtime/now (fn [_] (java.time.Instant/parse "2026-09-15T08:00:00Z"))
                  batch/apply! (fn [_ value] (reset! payload value) {:refs {}})]
      (board/finish! :rt review "reviewed" settlement))
    (let [strands (:strands @payload)
          root (first strands)
          comment-strands (filter #(= "true" (get-in % [:attributes :mr-review/comment])) strands)]
      (is (zero? (get-in root [:attributes :mr-review/curation-version])))
      (is (string? (get-in root [:attributes :mr-review/comment-revision])))
      (is (= 2 (count comment-strands)))
      (is (= #{"included"} (set (map #(get-in % [:attributes :mr-review/inclusion])
                                     comment-strands))))
      (is (= #{1} (set (map #(get-in % [:attributes :mr-review/candidate-version])
                            comment-strands))))
      (is (str/includes? (get-in (second strands) [:attributes :note/text])
                         "Fix the new-line behavior."))
      (is (not (str/includes? (get-in (second strands) [:attributes :note/text])
                              structured-result))))))

(deftest candidate-source-is-a-discriminated-union-without-null-variant-fields
  (let [base {:id "comment" :attributes
              {:mr-review/candidate-text "candidate"
               :mr-review/candidate-version 1
               :mr-review/original-text "candidate"
               :mr-review/reviewer "correctness" :mr-review/run "run-1"
               :mr-review/category "correctness" :mr-review/title "title"
               :mr-review/inclusion "included"
               :mr-review/position (pr-str {:kind "general" :reason "whole MR"})
               :mr-review/candidate-source
               (pr-str {:kind "reviewer" :reviewer "correctness" :runId "run-1"})}}
        reviewer-source (get-in (comments/comment-view base) [:candidate :source])
        adopted (assoc-in base [:attributes :mr-review/candidate-source]
                          (pr-str {:kind "user-adopted" :by "adam"
                                   :at "2026-09-15T08:00:00Z"}))
        adopted-source (get-in (comments/comment-view adopted) [:candidate :source])]
    (is (= {:kind "reviewer" :reviewer "correctness" :runId "run-1"}
           reviewer-source))
    (is (not (contains? reviewer-source :by)))
    (is (= {:kind "user-adopted" :by "adam" :at "2026-09-15T08:00:00Z"}
           adopted-source))
    (is (not (contains? adopted-source :reviewer)))
    (is (not (contains? (comments/comment-view base) :severity)))
    (is (not (contains? (:publication (comments/comment-view base)) :error)))
    (is (not (contains? (:publication (comments/comment-view base)) :discussionId)))))

(defn- fake-curation-batch! [review-state comment-state _rt payload]
  (Thread/sleep 20)
  (doseq [{:keys [ref attributes]} (:strands payload)]
    (if (= :review ref)
      (swap! review-state update :attributes merge attributes)
      (let [id (get (:refs payload) ref)]
        (swap! comment-state update id update :attributes merge attributes))))
  {:refs (:refs payload)})

(deftest curation-is-durable-versioned-and-a-real-concurrent-cas
  (let [lock (Object.)
        review-state (atom {:id "review" :state "active"
                            :attributes {:mr-review/review "true"
                                         :mr-review/stage "reviewed"
                                         :mr-review/decision "pending"
                                         :mr-review/current "true"
                                         :mr-review/comment-revision "revision"
                                         :mr-review/curation-version 0
                                         :mr-review/publication-state "unpublished"}})
        comment-state (atom {"comment" {:id "comment"
                                        :attributes {:mr-review/comment "true"
                                                     :mr-review/inclusion "included"
                                                     :mr-review/candidate-text "original"
                                                     :mr-review/candidate-version 1}}})
        request-a {:revision "revision" :expectedVersion 0 :by "a"
                   :changes [{:id "comment" :inclusion "dismissed"}]}
        request-b {:revision "revision" :expectedVersion 0 :by "b"
                   :changes [{:id "comment"
                              :candidate {:expectedVersion 1 :text "revised"}}]}
        call (fn [request]
               (try (views/curate! :rt "review" request)
                    (catch Exception error {:error (ex-message error)})))]
    (with-redefs [runtime/spool-state (fn [& _] {:lock lock})
                  runtime/now (fn [_] (java.time.Instant/parse "2026-09-15T08:00:00Z"))
                  views/require-review (fn [& _] @review-state)
                  board/comments (fn [& _] (vec (vals @comment-state)))
                  batch/apply! (partial fake-curation-batch! review-state comment-state)
                  views/comments-view (fn [& _]
                                        {:review {:curation
                                                  {:version (get-in @review-state
                                                                    [:attributes :mr-review/curation-version])}}})]
      (let [start (promise)
            runs (mapv (fn [request]
                         (future @start (call request)))
                       [request-a request-b])]
        (deliver start true)
        (let [results (mapv deref runs)]
          (is (= 1 (count (filter :error results))))
          (is (some #(re-find #"stale" (:error % "")) results)))))
    (is (= 1 (get-in @review-state [:attributes :mr-review/curation-version])))
    (let [comment (@comment-state "comment")]
      (is (or (and (= "dismissed" (get-in comment [:attributes :mr-review/inclusion]))
                   (= "original" (get-in comment [:attributes :mr-review/candidate-text])))
              (and (= "included" (get-in comment [:attributes :mr-review/inclusion]))
                   (= "revised" (get-in comment [:attributes :mr-review/candidate-text]))
                   (= 2 (get-in comment [:attributes :mr-review/candidate-version]))))))))

(deftest noncurrent-review-is-immutable-in-read-and-mutation
  (let [review {:id "review" :state "active"
                :attributes {:mr-review/review "true" :mr-review/stage "reviewed"
                             :mr-review/decision "pending" :mr-review/current "false"
                             :mr-review/comment-revision "revision"
                             :mr-review/curation-version 0
                             :mr-review/publication-state "unpublished"}}
        comment {:id "comment" :attributes {:mr-review/comment "true"
                                            :mr-review/candidate-text "text"
                                            :mr-review/candidate-version 1
                                            :mr-review/original-text "text"
                                            :mr-review/reviewer "r" :mr-review/run "run"
                                            :mr-review/category "r" :mr-review/title "title"
                                            :mr-review/inclusion "included"
                                            :mr-review/position (pr-str {:kind "general" :reason "MR"})
                                            :mr-review/candidate-source
                                            (pr-str {:kind "reviewer" :reviewer "r" :runId "run"})}}]
    (with-redefs [runtime/spool-state (fn [& _] {:lock (Object.)})
                  views/require-review (fn [& _] review)
                  views/summary (fn [& _] {:id "review" :current false})
                  board/comments (fn [& _] [comment])
                  batch/apply! (fn [& _] (throw (Exception. "Must not mutate")))]
      (is (false? (get-in (views/comments-view :rt "review") [:review :curation :mutable])))
      (is (thrown-with-msg? Exception #"current reviewed revision"
                            (views/curate! :rt "review"
                                           {:revision "revision" :expectedVersion 0 :by "adam"
                                            :changes [{:id "comment" :inclusion "dismissed"}]}))))))

(deftest curation-and-publication-boundary-reject-empty-and-invalid-requests
  (is (= {:revision "r" :expectedVersion 0 :by "a"
          :changes [{:id "c" :inclusion "dismissed"}]}
         (comments/normalize-curation-request
          {"revision" "r" "expectedVersion" 0 "by" "a"
           "changes" [{"id" "c" "inclusion" "dismissed"}]})))
  (is (= {:revision "r" :curationVersion 0}
         (comments/normalize-publish-request
          {"revision" "r" "curationVersion" 0})))
  (doseq [request [{:revision "r" :expectedVersion 0 :by "a" :changes []}
                   {:revision "r" :expectedVersion -1 :by "a"
                    :changes [{:id "c" :inclusion "included"}]}
                   {:revision "r" :expectedVersion 0 :by "a"
                    :changes [{:id "c" :inclusion "maybe"}]}
                   {:revision "r" :expectedVersion 0 :by "a"
                    :changes [{:id "c" :candidate {:expectedVersion 0 :text "x"}}]}]]
    (is (thrown? Exception (comments/normalize-curation-request request))))
  (doseq [request [{:revision "" :curationVersion 0}
                   {:revision "r" :curationVersion -1}
                   {:revision "r" :curationVersion 0 :text "browser-owned"}]]
    (is (thrown? Exception (comments/normalize-publish-request request)))))

(defn- fake-publication-batch! [review-state comment-state _rt payload]
  (doseq [{:keys [ref attributes]} (:strands payload)]
    (if (= :review ref)
      (swap! review-state update :attributes merge attributes)
      (let [id (get (:refs payload) ref)]
        (swap! comment-state update id update :attributes merge attributes))))
  {:refs (:refs payload)})

(deftest publication-is-marker-idempotent-retryable-and-never-finishes-review
  (let [mr {:project_id 7 :iid 9 :state "opened" :sha "head"
            :diff_refs {:base_sha "base" :start_sha "start" :head_sha "head"}}
        version {:base_commit_sha "base" :start_commit_sha "start"
                 :head_commit_sha "head"}
        diffs [{:old_path "a" :new_path "a" :diff "@@ -1 +1 @@\n-old\n+new"}]
        lock (Object.)
        review-state (atom {:id "review" :state "active"
                            :attributes {:mr-review/review "true"
                                         :mr-review/stage "reviewed"
                                         :mr-review/decision "pending"
                                         :mr-review/current "true"
                                         :mr-review/mr (pr-str mr)
                                         :mr-review/comment-revision "revision"
                                         :mr-review/curation-version 2
                                         :mr-review/publication-state "unpublished"}})
        comment-state
        (atom
         {"line" {:id "line" :attributes {:mr-review/comment "true"
                                          :mr-review/inclusion "included"
                                          :mr-review/candidate-text "line text"
                                          :mr-review/publication-status "unpublished"
                                          :mr-review/position
                                          (pr-str {:kind "line" :oldPath "a" :newPath "a"
                                                   :side "new" :line 1})}}
          "general" {:id "general" :attributes {:mr-review/comment "true"
                                                :mr-review/inclusion "included"
                                                :mr-review/candidate-text "general text"
                                                :mr-review/publication-status "unpublished"
                                                :mr-review/position
                                                (pr-str {:kind "general" :reason "MR"})}}
          "excluded" {:id "excluded" :attributes {:mr-review/comment "true"
                                                  :mr-review/inclusion "dismissed"
                                                  :mr-review/candidate-text "not sent"
                                                  :mr-review/publication-status "unpublished"
                                                  :mr-review/position
                                                  (pr-str {:kind "unsupported" :reason "binary"})}}})
        remote (atom {})
        posts (atom [])
        request {:revision "revision" :curationVersion 2}]
    (with-redefs [runtime/spool-state (fn [& _] {:lock lock})
                  runtime/now (fn [_] (java.time.Instant/parse "2026-09-15T08:00:00Z"))
                  views/require-review (fn [& _] @review-state)
                  board/comments (fn [& _] (vec (vals @comment-state)))
                  board/patch! (fn [_ strand attrs]
                                 (if (= "review" (:id strand))
                                   (do (swap! review-state update :attributes merge attrs) @review-state)
                                   (do (swap! comment-state update (:id strand) update :attributes merge attrs)
                                       (@comment-state (:id strand)))))
                  batch/apply! (partial fake-publication-batch! review-state comment-state)
                  review-io/publication-context (fn [& _] {:mr mr :version version :diffs diffs})
                  review-io/find-discussion (fn [_ _ marker] (@remote marker))
                  review-io/create-discussion!
                  (fn [_ _ _ _ body]
                    (swap! posts conj body)
                    (if (str/includes? body "general text")
                      (let [marker (last (str/split-lines body))]
                        (swap! remote assoc marker {:id "discussion-general"})
                        (throw (ex-info "ambiguous transport failure" {})))
                      {:id "discussion-line"}))]
      (let [first-result (publication/publish! :rt config "review" request)]
        (is (= "partial" (:state first-result)))
        (is (= #{"published" "reconciling"} (set (map :state (:comments first-result)))))
        (is (= "excluded" (get-in @comment-state ["excluded" :attributes
                                                  :mr-review/publication-status])))
        (is (= "active" (:state @review-state)))
        (is (= "pending" (get-in @review-state [:attributes :mr-review/decision]))))
      (let [retry (publication/publish! :rt config "review" request)]
        (is (= "published" (:state retry)))
        (is (= #{"published"} (set (map :state (:comments retry)))))
        (is (= 2 (count @posts)) "retry reconciles the marker and does not POST again")
        (is (= "active" (:state @review-state)))
        (is (= "pending" (get-in @review-state [:attributes :mr-review/decision])))))))

(deftest publication-validates-frozen-revision-and-nonempty-included-set
  (let [review {:id "review" :state "active"
                :attributes {:mr-review/review "true" :mr-review/stage "reviewed"
                             :mr-review/decision "pending" :mr-review/current "true"
                             :mr-review/comment-revision "revision"
                             :mr-review/curation-version 0}}
        called (atom false)]
    (with-redefs [runtime/spool-state (fn [& _] {:lock (Object.)})
                  views/require-review (fn [& _] review)
                  review-io/publication-context (fn [& _] (reset! called true) {})]
      (is (thrown-with-msg? Exception #"revision is stale"
                            (publication/publish! :rt config "review"
                                                  {:revision "old" :curationVersion 0})))
      (is (false? @called)))
    (let [mr {:project_id 7 :iid 9 :state "opened" :sha "head"
              :diff_refs {:base_sha "base" :start_sha "start" :head_sha "head"}}
          full-review (assoc-in review [:attributes :mr-review/mr] (pr-str mr))]
      (with-redefs [runtime/spool-state (fn [& _] {:lock (Object.)})
                    views/require-review (fn [& _] full-review)
                    board/comments (fn [& _] [])
                    review-io/publication-context
                    (fn [& _] {:mr mr
                               :version {:base_commit_sha "base" :start_commit_sha "start"
                                         :head_commit_sha "head"}
                               :diffs []})]
        (is (thrown-with-msg? Exception #"at least one included comment"
                              (publication/publish! :rt config "review"
                                                    {:revision "revision" :curationVersion 0})))))))

(deftest gitlab-discussion-boundary-emits-exact-anchor-side-and-range-fields
  (let [argvs (atom [])
        mr {:project_id 7 :iid 9}
        version {:base_commit_sha "base" :start_commit_sha "start"
                 :head_commit_sha "head"}
        position {:kind "line" :oldPath "old.clj" :newPath "new.clj"
                  :side "new" :line 4 :startSide "new" :startLine 2}]
    (with-redefs [review-io/command!
                  (fn [_ argv _ _] (swap! argvs conj argv) "{\"id\":\"discussion\"}")]
      (is (= "discussion" (:id (review-io/create-discussion! config mr version position "body"))))
      (review-io/create-discussion! config mr version
                                    {:kind "general" :reason "MR"} "overview"))
    (let [line-argv (first @argvs)
          general-argv (second @argvs)]
      (doseq [field ["position[base_sha]=base" "position[start_sha]=start"
                     "position[head_sha]=head" "position[old_path]=old.clj"
                     "position[new_path]=new.clj" "position[new_line]=4"
                     "position[line_range][start][type]=new"
                     "position[line_range][start][new_line]=2"
                     "position[line_range][end][new_line]=4"]]
        (is (some #{field} line-argv)))
      (is (not-any? #(str/starts-with? % "position[") general-argv)))))

(deftest gitlab-discussion-search-reads-every-page-before-proving-absence
  (let [pages (atom [])]
    (with-redefs [review-io/command!
                  (fn [_ argv _ _]
                    (let [endpoint (last argv)]
                      (swap! pages conj endpoint)
                      (json/write-str
                       (if (str/ends-with? endpoint "page=1")
                         (vec (repeat 100 {:id "earlier" :notes []}))
                         [{:id "target" :notes [{:body "body <!-- marker -->"}]}]))))]
      (is (= "target"
             (:id (review-io/find-discussion config {:project_id 7 :iid 9}
                                             "<!-- marker -->"))))
      (is (= ["projects/7/merge_requests/9/discussions?per_page=100&page=1"
              "projects/7/merge_requests/9/discussions?per_page=100&page=2"]
             @pages)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'millhouse.spools.auto-review-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
