(ns millhouse.land.internal.queue-cli
  "Private CLI contract for FIFO reservations and exact repair.")

(def arguments
  "Closed arguments for Land queue operations."
  {:op "merge-queue"
   :doc "Inspect or explicitly withdraw strict FIFO landing reservations."
   :subcommands
   {"join" {:doc "Reserve a run at its merge-turn gate; repeats retain its place."
            :hook-class :mutating :deadline-class :standard
            :positionals [{:name :run-id :required? true :spec :millhouse.land.merge-queue/non-blank}]}
    "status" {:doc "Show queue order or one entry with workflow progress."
              :hook-class :read :deadline-class :standard
              :positionals [{:name :entry-id :spec :millhouse.land.merge-queue/non-blank}]}
    "await" {:doc "Wait for a reserved turn; timeout never dequeues it."
             :hook-class :read :deadline-class :unbounded
             :flags {:timeout-secs {:type :int :spec :millhouse.land.merge-queue/timeout-secs
                                    :doc "Seconds to wait; defaults to 300."}}
             :positionals [{:name :entry-id :required? true :spec :millhouse.land.merge-queue/non-blank}]}
    "withdraw" {:doc "Stop a named landing and release its turn with an explicit reason."
                :hook-class :mutating :deadline-class :unbounded
                :flags {:reason {:type :string :required? true :spec :millhouse.land.merge-queue/non-blank}
                        :by-identity {:type :string :required? true :spec :millhouse.land.merge-queue/non-blank}}
                :positionals [{:name :entry-id :required? true :spec :millhouse.land.merge-queue/non-blank}]}
    "repair" {:doc "Resume and explicitly retry reversible preparation."
              :hook-class :mutating :deadline-class :unbounded
              :flags {:kind {:type :string :required? true
                             :doc "preparation."}
                      :by-identity {:type :string :required? true :spec :millhouse.land.merge-queue/non-blank
                                    :doc "Trusted actor performing the repair."}
                      :reason {:type :string :required? true :spec :millhouse.land.merge-queue/non-blank}
                      :evidence {:type :string :parse :json :required? true
                                 :doc "Exact root-id, gate-id, expected-attempt and request-id."}}
              :positionals [{:name :run-id :required? true :spec :millhouse.land.merge-queue/non-blank}]}}})

