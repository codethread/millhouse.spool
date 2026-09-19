(ns millhouse.spools.identity-test
  "Focused lifecycle tests for native startup identity resolution."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.spools.identity :as identity]
            [millhouse.test-support :as test-support]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.peers.alpha :as peers]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.test.alpha :as test-alpha]))

(defn- identities [runtime]
  (filterv identity/identity? (weaver/list runtime)))

(defn- identity-edges [runtime edge-type]
  (graph/outgoing-edges runtime (mapv :id (identities runtime)) edge-type))

(defn- failure [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(defn- activate-identity! [runtime]
  (test-support/activate-spool!
   runtime :millhouse/spools-identity 'millhouse.spools.identity))

(defn- from-argv [runtime argv]
  (weaver/op! runtime 'identity argv))

(defn- graph-snapshot [runtime]
  {:strands (weaver/list runtime)
   :parents (identity-edges runtime "parent-of")
   :runs (identity-edges runtime "performed")})

(defn- add-named-identity! [runtime friendly-id]
  (weaver/add! runtime
               {:title friendly-id
                :attributes {:identity/session "true"
                             :identity/id friendly-id
                             :identity/harness "test"
                             :identity/native-session-id (str "native-" friendly-id)}}))

(defn- attribution-edges [runtime source-id relation]
  (graph/incoming-edges runtime [source-id] relation))

(deftest attribution-reconciliation-resolves-only-exact-unique-local-identities
  (test-support/with-runtime
    (fn [runtime _]
      (let [known (add-named-identity! runtime "known-kind-otter")
            _ (add-named-identity! runtime "duplicate-kind-otter")
            _ (add-named-identity! runtime "duplicate-kind-otter")
            sources (into {}
                          (map (fn [[label friendly-id]]
                                 [label
                                  (weaver/add!
                                   runtime
                                   {:title (name label)
                                    :attributes {:identity/by-identity friendly-id}})]))
                          {:known "known-kind-otter"
                           :unknown "missing-kind-otter"
                           :ambiguous "duplicate-kind-otter"
                           :fuzzy "known-kind-otte"})
            ignored (weaver/add!
                     runtime
                     {:title "not inferred"
                      :attributes {:identity/owner "known-kind-otter"}})
            result (identity/reconcile-attributions! runtime)
            statuses (into {} (map (juxt :source-id :status))
                           (identity/inspect-attributions runtime))]
        (is (= {:scanned 4 :resolved 1 :unresolved 2 :ambiguous 1
                :absent 0 :writes 1}
               result))
        (is (= :resolved (get statuses (:id (:known sources)))))
        (is (= :unresolved (get statuses (:id (:unknown sources)))))
        (is (= :ambiguous (get statuses (:id (:ambiguous sources)))))
        (is (= :unresolved (get statuses (:id (:fuzzy sources)))))
        (is (nil? (get statuses (:id ignored))))
        (is (= [{:from_strand_id (:id known)
                 :to_strand_id (:id (:known sources))
                 :edge_type "attributed"}]
               (mapv #(select-keys % [:from_strand_id :to_strand_id :edge_type])
                     (attribution-edges runtime (:id (:known sources)) "attributed"))))
        (is (empty? (attribution-edges runtime (:id (:unknown sources)) "attributed")))
        (is (empty? (attribution-edges runtime (:id (:ambiguous sources)) "attributed")))
        (is (empty? (attribution-edges runtime (:id (:fuzzy sources)) "attributed")))))))

(deftest explicit-spool-contributions-keep-role-relations-distinct
  (test-support/with-runtime
    (fn [runtime _]
      (activate-identity! runtime)
      (test-support/activate-spool!
       runtime :identity-attribution-fixture
       'millhouse.test-modules.identity-attribution
       :after [:millhouse/spools-identity])
      (let [caller (add-named-identity! runtime "caller-kind-otter")
            source (weaver/add!
                    runtime
                    {:title "call"
                     :attributes {:support/caller-identity "caller-kind-otter"}})]
        (test-alpha/await-quiescent! runtime)
        (is (= [{:key :identity/by-identity
                 :attribute :identity/by-identity
                 :relation "attributed"}
                {:key :support/caller
                 :attribute :support/caller-identity
                 :relation "called"}]
               (identity/attribution-contributions runtime)))
        (is (= [(:id caller)]
               (mapv :from_strand_id
                     (attribution-edges runtime (:id source) "called"))))
        (is (empty? (attribution-edges runtime (:id source) "attributed")))))))

(deftest attribution-events-handle-late-identities-batch-fanout-and-edits
  (test-support/with-runtime
    (fn [runtime _]
      (activate-identity! runtime)
      (let [late-source (weaver/add!
                         runtime
                         {:title "late"
                          :attributes {:identity/by-identity "late-kind-otter"}})]
        (test-alpha/await-quiescent! runtime)
        (is (= :unresolved
               (:status (first (identity/inspect-attributions
                                runtime [(:id late-source)])))))
        (let [late (add-named-identity! runtime "late-kind-otter")]
          (test-alpha/await-quiescent! runtime)
          (is (= (:id late)
                 (:from_strand_id
                  (first (attribution-edges runtime (:id late-source) "attributed")))))
          (add-named-identity! runtime "late-kind-otter")
          (test-alpha/await-quiescent! runtime)
          (is (= :ambiguous
                 (:status (first (identity/inspect-attributions
                                  runtime [(:id late-source)])))))
          (is (empty? (attribution-edges runtime (:id late-source) "attributed")))))
      (let [result (batch/apply!
                    runtime
                    {:refs {}
                     :strands [{:ref :identity
                                :title "batch-kind-otter"
                                :attributes {:identity/session "true"
                                             :identity/id "batch-kind-otter"
                                             :identity/harness "test"
                                             :identity/native-session-id "batch-native"}}
                               {:ref :source
                                :title "batch source"
                                :attributes {:identity/by-identity "batch-kind-otter"}}]
                     :edges []
                     :burn []})
            identity-id (get-in result [:refs :identity])
            source-id (get-in result [:refs :source])]
        (test-alpha/await-quiescent! runtime)
        (is (= [identity-id]
               (mapv :from_strand_id
                     (attribution-edges runtime source-id "attributed"))))
        (let [replacement (add-named-identity! runtime "replacement-kind-otter")]
          (weaver/update! runtime source-id
                          {:attributes {:identity/by-identity
                                        "replacement-kind-otter"}})
          (test-alpha/await-quiescent! runtime)
          (is (= [(:id replacement)]
                 (mapv :from_strand_id
                       (attribution-edges runtime source-id "attributed"))))
          (weaver/update! runtime source-id
                          {:attributes {:identity/by-identity nil}})
          (test-alpha/await-quiescent! runtime)
          (is (empty? (attribution-edges runtime source-id "attributed")))
          (is (empty? (identity/inspect-attributions runtime [source-id]))))))))

(deftest duplicate-delivery-and-repeated-reconciliation-write-only-on-change
  (test-support/with-runtime
    (fn [runtime _]
      (let [_ (add-named-identity! runtime "once-kind-otter")
            source (weaver/add!
                    runtime
                    {:title "source"
                     :attributes {:identity/by-identity "once-kind-otter"}})
            writes (atom 0)
            apply! batch/apply!]
        (with-redefs [batch/apply! (fn [& args]
                                     (swap! writes inc)
                                     (apply apply! args))]
          (let [first-delivery (identity/on-attribution-event
                                {:event/type :strand/added})
                duplicate-delivery (identity/on-attribution-event
                                    {:event/type :strand/added})]
            (is (= 1 (:writes first-delivery)))
            (is (zero? (:writes duplicate-delivery)))
            (test-alpha/await-quiescent! runtime)
            (reset! writes 0)
            (is (zero? (:writes (identity/reconcile-attributions! runtime))))
            (is (zero? @writes))))
        (is (= 1 (count (attribution-edges runtime (:id source) "attributed"))))))))

(deftest activation-recovers-attribution-from-durable-sources-after-restart
  (let [root (test-support/temp-dir "millhouse-attribution-restart")
        ids (atom nil)]
    (try
      (test-alpha/run-with-weaver-world
       {:root root}
       (fn [{runtime :runtime}]
         (let [agent (add-named-identity! runtime "restart-kind-otter")
               source (weaver/add!
                       runtime
                       {:title "durable source"
                        :attributes {:identity/by-identity "restart-kind-otter"}})]
           (reset! ids {:agent (:id agent) :source (:id source)})
           (is (empty? (attribution-edges runtime (:id source) "attributed"))))))
      (test-alpha/run-with-weaver-world
       {:root root}
       (fn [{runtime :runtime}]
         (activate-identity! runtime)
         (is (= [(:agent @ids)]
                (mapv :from_strand_id
                      (attribution-edges runtime (:source @ids) "attributed"))))))
      (finally
        (test-support/delete-tree! root)))))

(deftest malformed-attribution-is-visible-and-fails-reconciliation-loudly
  (test-support/with-runtime
    (fn [runtime _]
      (let [source (weaver/add!
                    runtime
                    {:title "malformed"
                     :attributes {:identity/by-identity ""}})
            projection (first (identity/inspect-attributions runtime [(:id source)]))]
        (is (= :malformed (:status projection)))
        (is (= "" (:identity projection)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Malformed identity attribution evidence"
                              (identity/reconcile-attributions! runtime)))))))

(deftest startup-mints-once-and-recovers-without-launcher-state
  (test-support/with-runtime
    (fn [runtime _]
      (let [request {:harness "pi"
                     :native-session-id "native-1"
                     :model "claude-sonnet"
                     :thinking-level "high"}
            fresh (identity/startup! runtime request)
            resumed (identity/startup! runtime request)
            record (identity/current runtime (:identity fresh))]
        (is (= #{:identity :strand-id :result :instruction} (set (keys fresh))))
        (is (re-matches #"[a-z]+-[a-z]+-[a-z]+" (:identity fresh)))
        (is (= "minted" (:result fresh)))
        (is (= (assoc fresh :result "recovered") resumed))
        (is (= (str "Your Millstrand identity is " (:identity fresh)
                    ". Use it as `--owner " (:identity fresh)
                    "` for `kanban claim` and `--by-identity " (:identity fresh)
                    "` for Kanban notes, workflow mutations, and agent operations. "
                    "Keep `--identity` and `--parent-identity` for native-session references. "
                    "Inspect live help; never pass an unsupported flag or invent another identity.")
               (:instruction fresh)))
        (is (= "pi" (attr-get record :identity/harness)))
        (is (= "native-1" (attr-get record :identity/native-session-id)))
        (is (= "claude-sonnet" (attr-get record :identity/model)))
        (is (= 1 (count (identities runtime))))))))

(deftest startup-lock-persists-in-ignored-workspace-state
  (test-support/with-runtime
    (fn [runtime config-dir]
      (test-support/run-git! config-dir "init" "--quiet")
      (spit (io/file config-dir ".gitignore") "state/\ndata/\n")
      (test-support/run-git! config-dir "add" ".")
      (test-support/run-git! config-dir
                             "-c" "user.name=Millhouse Test"
                             "-c" "user.email=millhouse@example.invalid"
                             "commit" "--quiet" "-m" "fixture")
      (is (str/blank? (test-support/run-git! config-dir "status" "--porcelain")))
      (identity/startup! runtime {:harness "pi" :native-session-id "clean-checkout"})
      (is (.isFile (io/file config-dir "state" ".identity-lock.acquire")))
      (is (not (.exists (io/file config-dir ".identity-lock.acquire"))))
      (is (str/blank? (test-support/run-git! config-dir "status" "--porcelain"))))))

(deftest bind-remains-compatible-and-expected-identity-never-mints-an-orphan
  (test-support/with-runtime
    (fn [runtime _]
      (testing "maintenance callers retain the legacy result shape"
        (doseq [harness ["claude" "cursor"]]
          (let [run (weaver/add! runtime {:title "run"})
                request {:harness harness
                         :native-session-id (str harness "-session")
                         :run-id (:id run)}
                fresh (identity/bind! runtime request)
                resumed (identity/bind! runtime
                                        (assoc request :expected-identity
                                               (:identity fresh)))]
            (is (= #{:identity :strand-id :resumed :prompt} (set (keys fresh))))
            (is (false? (:resumed fresh)))
            (is (= (assoc fresh :resumed true) resumed))
            (is (= [(:id run)]
                   (mapv :to_strand_id
                         (graph/outgoing-edges runtime [(:strand-id fresh)] "performed")))))))
      (testing "an assertion without a native binding fails before minting"
        (let [before (count (identities runtime))
              error (failure #(identity/bind!
                               runtime
                               {:harness "cursor"
                                :native-session-id "missing-session"
                                :expected-identity "wrong-calm-otter"}))]
          (is (re-find #"does not match expectation" (ex-message error)))
          (is (nil? (:actual (ex-data error))))
          (is (= before (count (identities runtime)))))))))

(deftest supplied-identity-must-resolve-to-the-exact-native-binding
  (test-support/with-runtime
    (fn [runtime _]
      (let [bound (identity/startup! runtime {:harness "codex"
                                              :native-session-id "native-bound"})
            other (identity/startup! runtime {:harness "codex"
                                              :native-session-id "native-other"})
            baseline (graph-snapshot runtime)]
        (testing "the exact existing binding is a valid session-scoped reference"
          (is (= "recovered"
                 (:result
                  (identity/startup! runtime {:harness "codex"
                                              :native-session-id "native-bound"
                                              :identity (:identity bound)})))))
        (testing "unknown and conflicting references never write"
          (doseq [[request message]
                  [[{:harness "codex"
                     :native-session-id "native-new"
                     :identity "unknown-calm-otter"}
                    #"does not resolve uniquely"]
                   [{:harness "codex"
                     :native-session-id "native-bound"
                     :identity (:identity other)}
                    #"another native session"]]]
            (let [error (failure #(identity/startup! runtime request))]
              (is (re-find message (ex-message error)))
              (is (= baseline (graph-snapshot runtime))))))
        (testing "an ambiguous friendly identity fails before native minting"
          (doseq [session ["duplicate-a" "duplicate-b"]]
            (weaver/add! runtime
                         {:title "duplicate-calm-otter"
                          :attributes {:identity/session "true"
                                       :identity/id "duplicate-calm-otter"
                                       :identity/harness "codex"
                                       :identity/native-session-id session}}))
          (let [before (graph-snapshot runtime)
                error (failure #(identity/startup!
                                 runtime
                                 {:harness "codex"
                                  :native-session-id "duplicate-new"
                                  :identity "duplicate-calm-otter"}))]
            (is (re-find #"does not resolve uniquely" (ex-message error)))
            (is (= before (graph-snapshot runtime)))))))))

(deftest invalid-provenance-targets-cause-no-partial-identity-or-edge
  (test-support/with-runtime
    (fn [runtime _]
      (testing "a missing run is validated before mint"
        (let [error (failure #(identity/startup!
                               runtime
                               {:harness "pi"
                                :native-session-id "invalid-run"
                                :run-id "not-a-run"}))]
          (is (re-find #"run target not found" (ex-message error)))
          (is (empty? (identities runtime)))))
      (testing "a missing parent is validated before mint"
        (let [error (failure #(identity/startup!
                               runtime
                               {:harness "pi"
                                :native-session-id "invalid-parent"
                                :parent-identity "missing-parent"}))]
          (is (re-find #"does not resolve uniquely" (ex-message error)))
          (is (empty? (identities runtime)))
          (is (empty? (identity-edges runtime "parent-of")))
          (is (empty? (identity-edges runtime "performed"))))))))

(deftest parent-and-run-provenance-is-idempotent-without-self-edges
  (test-support/with-runtime
    (fn [runtime _]
      (let [parent (identity/startup! runtime {:harness "codex"
                                               :native-session-id "parent-session"})
            run (weaver/add! runtime {:title "managed run"})
            child-request {:harness "codex"
                           :native-session-id
                           (identity/codex-child-session-id "parent-session" "agent-1")
                           :parent-identity (:identity parent)
                           :run-id (:id run)}
            child (identity/startup! runtime child-request)]
        (identity/startup! runtime child-request)
        (is (= [{:from_strand_id (:strand-id parent)
                 :to_strand_id (:strand-id child)
                 :edge_type "parent-of"}]
               (mapv #(select-keys % [:from_strand_id :to_strand_id :edge_type])
                     (identity-edges runtime "parent-of"))))
        (is (= [{:from_strand_id (:strand-id child)
                 :to_strand_id (:id run)
                 :edge_type "performed"}]
               (mapv #(select-keys % [:from_strand_id :to_strand_id :edge_type])
                     (identity-edges runtime "performed"))))
        (identity/startup! runtime {:harness "codex"
                                    :native-session-id "parent-session"
                                    :parent-identity (:identity parent)})
        (is (not-any? #(= (:from_strand_id %) (:to_strand_id %))
                      (identity-edges runtime "parent-of")))))))

(deftest reservations-attach-once-and-cannot-be-stolen-or-rebound
  (test-support/with-runtime
    (fn [runtime _]
      (let [reserved (identity/reserve! runtime {:harness "codex"
                                                 :model "gpt-5"})
            request {:harness "codex"
                     :native-session-id "actual-thread"
                     :reservation-id (:reservation-id reserved)
                     :identity (:identity reserved)}]
        (is (= #{:identity :strand-id :result :reservation-id}
               (set (keys reserved))))
        (is (= "reserved" (:result reserved)))
        (testing "a friendly name without its capability cannot attach"
          (let [error (failure #(identity/startup!
                                 runtime
                                 {:harness "codex"
                                  :native-session-id "name-only-thread"
                                  :identity (:identity reserved)}))]
            (is (re-find #"requires its reservation" (ex-message error)))
            (is (= 1 (count (identities runtime))))))
        (testing "first attachment and exact replay converge"
          (let [attached (identity/attach! runtime request)
                replay (identity/startup! runtime request)
                supplied-recovery (identity/startup!
                                   runtime
                                   {:harness "codex"
                                    :native-session-id "actual-thread"
                                    :identity (:identity reserved)})
                record (identity/current runtime (:identity reserved))]
            (is (= "attached" (:result attached)))
            (is (= attached replay))
            (is (= "recovered" (:result supplied-recovery)))
            (is (= "actual-thread" (attr-get record :identity/native-session-id)))
            (is (= "attached" (attr-get record :identity/reservation-state)))
            (is (= 1 (count (identities runtime))))))
        (testing "another session or harness cannot take the reservation"
          (doseq [conflict [(assoc request :native-session-id "stolen-thread")
                            (assoc request :harness "pi")]]
            (let [before (weaver/list runtime)
                  error (failure #(identity/attach! runtime conflict))]
              (is (re-find #"another native session|another harness"
                           (ex-message error)))
              (is (= before (weaver/list runtime))))))
        (testing "a reservation cannot replace an occupied native binding"
          (let [blocked (identity/reserve! runtime {:harness "codex"})
                occupied (identity/startup! runtime {:harness "codex"
                                                     :native-session-id "occupied-thread"})
                before (weaver/list runtime)
                error (failure #(identity/attach!
                                 runtime
                                 {:harness "codex"
                                  :native-session-id "occupied-thread"
                                  :reservation-id (:reservation-id blocked)}))
                recovered (identity/startup! runtime {:harness "codex"
                                                      :native-session-id "occupied-thread"})]
            (is (re-find #"already bound to another identity" (ex-message error)))
            (is (= before (weaver/list runtime)))
            (is (= (:identity occupied) (:identity recovered)))))))))

(deftest rejected-attachment-leaves-binding-and-provenance-unchanged
  (test-support/with-runtime
    (fn [runtime _]
      (let [reserved (identity/reserve! runtime {:harness "codex"})
            parent (identity/startup! runtime {:harness "pi" :native-session-id "parent"})
            run (weaver/add! runtime {:title "run"})
            request {:harness "codex"
                     :native-session-id "actual-thread"
                     :reservation-id (:reservation-id reserved)
                     :parent-identity (:identity parent)
                     :run-id (:id run)}]
        (doseq [invalid [(assoc request :identity "unknown-identity")
                         (assoc request :identity (:identity parent))
                         (assoc request :reservation-id "unknown-reservation")
                         (assoc request :parent-identity "unknown-parent")
                         (assoc request :run-id "unknown-run")
                         ;; The run resolves, but its self-edge is rejected by
                         ;; the transaction after the binding/parent edge writes.
                         (assoc request :run-id (:strand-id reserved))]]
          (let [before (graph-snapshot runtime)]
            (is (some? (failure #(identity/attach! runtime invalid))))
            (is (= before (graph-snapshot runtime)))))
        (is (= "attached" (:result (identity/attach! runtime request))))
        (identity/attach! runtime request)
        (is (= 1 (count (identity-edges runtime "parent-of"))))
        (is (= 1 (count (identity-edges runtime "performed"))))))))

(deftest simultaneous-reservation-attachment-has-one-native-winner
  (test-support/with-runtime
    (fn [runtime _]
      (let [reserved (identity/reserve! runtime {:harness "codex"})
            parent (identity/startup! runtime {:harness "pi" :native-session-id "parent"})
            run (weaver/add! runtime {:title "run"})
            start (promise)
            workers (mapv (fn [n]
                            (future
                              @start
                              (try
                                (identity/attach!
                                 runtime
                                 {:harness "codex"
                                  :native-session-id (str "thread-" (mod n 2))
                                  :reservation-id (:reservation-id reserved)
                                  :parent-identity (:identity parent)
                                  :run-id (:id run)})
                                (catch clojure.lang.ExceptionInfo error error))))
                          (range 16))]
        (deliver start true)
        (let [results (mapv deref workers)
              attached (filter map? results)
              rejected (remove map? results)]
          (is (= 8 (count attached) (count rejected)))
          (is (every? #(= "attached" (:result %)) attached))
          (is (= #{(:identity reserved)} (set (map :identity attached))))
          (is (every? #(re-find #"already attached to another native session"
                                (ex-message %)) rejected))
          (is (= 2 (count (identities runtime))))
          (is (= 1 (count (identity-edges runtime "parent-of"))))
          (is (= 1 (count (identity-edges runtime "performed")))))))))

(deftest simultaneous-startup-converges-on-one-binding-and-provenance-set
  (test-support/with-runtime
    (fn [runtime _]
      (let [parent (identity/startup! runtime {:harness "pi"
                                               :native-session-id "parent-session"})
            run (weaver/add! runtime {:title "concurrent run"})
            start (promise)
            request {:harness "pi"
                     :native-session-id "concurrent-session"
                     :parent-identity (:identity parent)
                     :run-id (:id run)}
            workers (mapv (fn [_]
                            (future
                              @start
                              (identity/startup! runtime request)))
                          (range 16))]
        (deliver start true)
        (let [results (mapv deref workers)]
          (is (= 1 (count (set (map :identity results)))))
          (is (= 1 (count (filter #(= "minted" (:result %)) results))))
          (is (= 15 (count (filter #(= "recovered" (:result %)) results))))
          (is (= 2 (count (identities runtime))))
          (is (= 1 (count (identity-edges runtime "parent-of"))))
          (is (= 1 (count (identity-edges runtime "performed")))))))))

(deftest native-bindings-recover-after-a-cold-runtime-restart
  (let [root (test-support/temp-dir "millhouse-identity-restart")
        first-result (atom nil)]
    (try
      (test-alpha/run-with-weaver-world
       {:root root}
       (fn [{runtime :runtime}]
         (reset! first-result
                 (identity/startup! runtime {:harness "pi"
                                             :native-session-id "resume-session"}))))
      (test-alpha/run-with-weaver-world
       {:root root}
       (fn [{runtime :runtime}]
         (let [resumed (identity/startup! runtime {:harness "pi"
                                                   :native-session-id "resume-session"})]
           (is (= (:identity @first-result) (:identity resumed)))
           (is (= "recovered" (:result resumed)))
           (is (= 1 (count (identities runtime)))))))
      (finally
        (test-support/delete-tree! root)))))

(deftest separate-workspaces-resolve-identities-independently
  (test-support/with-runtime
    {:prefix "identity-workspace-a"}
    (fn [runtime-a _]
      (test-support/with-runtime
        {:prefix "identity-workspace-b"}
        (fn [runtime-b _]
          (let [request {:harness "pi" :native-session-id "same-native-id"}
                result-a (identity/startup! runtime-a request)
                result-b (identity/startup! runtime-b request)]
            (is (= "minted" (:result result-a)))
            (is (= "minted" (:result result-b)))
            (is (= 1 (count (identities runtime-a))))
            (is (= 1 (count (identities runtime-b))))))))))

(deftest codex-child-keys-are-unambiguous-and-never-equal-the-parent
  (let [parent "parent:session"
        child (identity/codex-child-session-id parent "agent:1")]
    (is (str/starts-with? child "codex-child:v1:"))
    (is (not= parent child))
    (is (not= (identity/codex-child-session-id "a:b" "c")
              (identity/codex-child-session-id "a" "b:c")))
    (is (= child (identity/codex-child-session-id parent "agent:1")))))

(deftest cli-contract-parses-native-startup-reservation-and-child-key
  (test-support/with-runtime
    (fn [runtime _]
      (activate-identity! runtime)
      (let [fresh (from-argv runtime ["startup" "pi" "cli-session"])
            resumed (from-argv runtime ["startup" "--identity" (:identity fresh)
                                        "pi" "cli-session"])
            reserved (from-argv runtime ["reserve" "codex"])
            attached (from-argv runtime ["attach"
                                         "--identity" (:identity reserved)
                                         "codex" "cli-thread"
                                         (:reservation-id reserved)])
            child-key (from-argv runtime ["codex-child-key" "parent" "agent"])
            actor (add-named-identity! runtime "cli-kind-otter")
            source (weaver/add!
                    runtime
                    {:title "CLI attribution"
                     :attributes {:identity/by-identity "cli-kind-otter"}})
            attributions (from-argv runtime ["attributions" (:id source)])
            reconciled (from-argv runtime ["reconcile" (:id source)])]
        (is (= "minted" (:result fresh)))
        (is (= "recovered" (:result resumed)))
        (is (= "reserved" (:result reserved)))
        (is (= "attached" (:result attached)))
        (is (= #{:operation :identity :strand-id :result :instruction}
               (set (keys fresh)) (set (keys resumed)) (set (keys attached))))
        (is (= #{:operation :identity :strand-id :result :reservation-id}
               (set (keys reserved))))
        (is (= ["identity startup" "identity startup" "identity reserve" "identity attach"]
               (mapv :operation [fresh resumed reserved attached])))
        (is (= {:operation "identity codex-child-key"
                :native-session-id (identity/codex-child-session-id "parent" "agent")}
               child-key))
        (is (= "identity attributions" (:operation attributions)))
        (is (= {:source-id (:id source)
                :identity "cli-kind-otter"
                :status :resolved
                :identity-strand-ids [(:id actor)]}
               (select-keys (first (:attributions attributions))
                            [:source-id :identity :status :identity-strand-ids])))
        (is (= "identity reconcile" (:operation reconciled)))
        (is (= 1 (:resolved reconciled)))))))

(defn- with-registration-world [f]
  (test-support/with-runtime
    (fn [origin origin-dir]
      (test-support/with-runtime
        (fn [target target-dir]
          (activate-identity! origin)
          (activate-identity! target)
          (let [rows (atom [{:weaver-id "origin-id"
                             :workspace (.getCanonicalPath (io/file origin-dir))
                             :running? true}
                            {:weaver-id "target-id"
                             :workspace (.getCanonicalPath (io/file target-dir))
                             :running? true}])]
            (with-redefs [peers/peers #(deref rows)
                          peers/call! (fn [peer op args]
                                        (is (= "identity" op))
                                        ;; The peer transport returns string-keyed JSON.
                                        (json/read-str
                                         (json/write-str
                                          (from-argv (if (= (.getCanonicalPath (io/file origin-dir))
                                                            (:workspace peer))
                                                       origin target)
                                                     (:argv args))
                                          :key-fn #(if (keyword? %)
                                                     (subs (str %) 1)
                                                     (str %)))))]
              (f origin target rows))))))))

(deftest registration-verifies-origin-and-copies-only-the-descriptor
  (with-registration-world
    (fn [origin target rows]
      (let [parent (identity/startup! origin {:harness "pi" :native-session-id "parent"})
            run (weaver/add! origin {:title "run"})
            reserved (identity/reserve! origin {:harness "codex" :model "astra"})
            bound (identity/attach! origin {:harness "codex" :native-session-id "thread"
                                            :reservation-id (:reservation-id reserved)
                                            :parent-identity (:identity parent) :run-id (:id run)})
            name (:identity bound)
            source (weaver/add!
                    target
                    {:title "waiting for registration"
                     :attributes {:identity/by-identity name}})
            argv ["register" name "--to-weaver" "target-id" "--by-identity" name]
            result (from-argv origin argv)
            _ (test-alpha/await-quiescent! target)
            record (identity/current target name)
            baseline (graph-snapshot target)]
        (is (= "registered" (:result result)))
        (is (= [(:id record)]
               (mapv :from_strand_id
                     (attribution-edges target (:id source) "attributed"))))
        (is (= "thread" (attr-get record :identity/native-session-id)))
        (is (= "astra" (attr-get record :identity/model)))
        (is (= (:workspace (first @rows)) (attr-get record :identity/origin-workspace)))
        (is (= (:strand-id bound) (attr-get record :identity/origin-strand-id)))
        (is (nil? (attr-get record :identity/reservation-id)))
        (is (nil? (attr-get record :identity/reservation-state)))
        (is (empty? (:parents baseline)))
        (is (empty? (:runs baseline)))
        (is (= "existing" (:result (from-argv origin argv))))
        (is (= baseline (graph-snapshot target)))
        (swap! rows update 0 assoc :weaver-id "origin-id-after-restart")
        (is (= "existing" (:result (from-argv origin argv))))
        (is (= baseline (graph-snapshot target)))
        (is (= "recovered" (:result (identity/startup!
                                     target {:harness "codex" :native-session-id "thread"
                                             :identity name}))))))))

(deftest registration-rejects-binding-and-origin-conflicts-without-writes
  (doseq [conflict [:name :session :origin]]
    (with-registration-world
      (fn [origin target _]
        (let [bound (identity/startup! origin {:harness "codex" :native-session-id "thread"})
              name (:identity bound)]
          (case conflict
            :name (weaver/add! target {:title name
                                       :attributes {:identity/session "true" :identity/id name
                                                    :identity/harness "codex"
                                                    :identity/native-session-id "another-thread"}})
            :session (identity/startup! target {:harness "codex" :native-session-id "thread"})
            :origin (do
                      (identity/register! origin name "target-id" name)
                      (weaver/update! target (:id (identity/current target name))
                                      {:attributes {:identity/origin-strand-id "another-strand"}})))
          (let [before (graph-snapshot target)
                error (failure #(identity/register! origin name "target-id" name))]
            (is (re-find #"conflicts" (ex-message error)))
            (is (= before (graph-snapshot target)))))))))

(deftest registration-preserves-and-validates-forwarded-origin
  (with-registration-world
    (fn [origin target _]
      (let [bound (identity/startup! origin {:harness "codex" :native-session-id "thread"})
            name (:identity bound)
            record-id (:id (identity/current origin name))]
        (weaver/update! origin record-id
                        {:attributes {:identity/origin-workspace "/original/.millstrand"
                                      :identity/origin-strand-id "original-strand"}})
        (identity/register! origin name "target-id" name)
        (let [registered (identity/current target name)]
          (is (= "/original/.millstrand"
                 (attr-get registered :identity/origin-workspace)))
          (is (= "original-strand" (attr-get registered :identity/origin-strand-id)))))))
  (with-registration-world
    (fn [origin target _]
      (let [bound (identity/startup! origin {:harness "codex" :native-session-id "thread"})
            name (:identity bound)]
        (weaver/update! origin (:id (identity/current origin name))
                        {:attributes {:identity/origin-workspace "/partial/.millstrand"}})
        (is (re-find #"incomplete registration provenance"
                     (ex-message (failure #(identity/register! origin name "target-id" name)))))
        (is (empty? (identities target)))))))

(deftest registration-rejects-unattached-origin-and-unknown-routing
  (with-registration-world
    (fn [origin target _]
      (let [reserved (identity/reserve! origin {:harness "codex"})
            bound (identity/startup! origin {:harness "codex" :native-session-id "thread"})
            name (:identity bound)
            other (:identity (identity/startup! origin {:harness "pi"
                                                        :native-session-id "other"}))]
        (doseq [argv [["register" (:identity reserved) "--to-weaver" "target-id"
                       "--by-identity" (:identity reserved)]
                      ["register" name "--to-weaver" "missing" "--by-identity" name]
                      ["register" name "--to-weaver" "origin-id" "--by-identity" name]
                      ["register" name "--to-weaver" "target-id" "--by-identity" "missing"]
                      ["register" name "--to-weaver" "target-id" "--by-identity" other]
                      ["receive" "missing" "--from-weaver" "origin-id"]
                      ["receive" (:identity reserved) "--from-weaver" "origin-id"]]]
          (is (some? (failure #(from-argv (if (= "receive" (first argv)) target origin) argv))))
          (is (empty? (identities target))))))))
