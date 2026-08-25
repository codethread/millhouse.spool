(ns millhouse.spools.millstrand-workflows.unchanged-proof
  "Shared fixtures for the unchanged refresh proof suites."
  (:require [clojure.string :as str]))

(def refresh-fixture
  "Return accepted pre-refresh roots plus unchanged refresh/runtime evidence."
  {:refresh {:status :unchanged
             :mode :full
             :modules
             {'demo/unchanged {:module/key 'demo/unchanged
                               :status :unchanged}}
             :roots {}
             :residuals []
             :conflicts []
             :remedies []}
   :runtime-status {:pending-generation nil}
   :current-roots {['demo/family 'demo/root] "/tmp/demo"}
   :pre-refresh-status
   {:families
    {'demo/family
     {:roots
      {'demo/root {:status :synced
                   :sync {:lib 'demo/root
                          :family 'demo/family
                          :coordinate {:local/root "/tmp/demo"}
                          :kind :local
                          :root "/tmp/demo"
                          :source {:kind :local
                                   :file "/tmp/demo/spools.edn"}
                          :provenance :spools-edn
                          :status :already-available}}}}}}})

(def ^:private unchanged-module-forbidden-keys
  #{:error :reason :refusal :root/outcome :dependency :dependency/outcome})

(defn valid?
  "Return whether pre-refresh roots and unchanged runtime evidence agree."
  [{:keys [refresh runtime-status pre-refresh-status current-roots]}]
  (let [modules (:modules refresh)
        families (:families pre-refresh-status)
        intended-families (set (map first (keys current-roots)))
        observed-families (when (map? families) (set (keys families)))
        observed-roots
        (when (map? families)
          (set (mapcat (fn [[family projection]]
                         (when (map? (:roots projection))
                           (map (fn [[root _]] [family root])
                                (:roots projection))))
                       families)))]
    (and (= :unchanged (:status refresh))
         (= :full (:mode refresh))
         (map? modules)
         (every? (fn [[module-key outcome]]
                   (and (= module-key (:module/key outcome))
                        (= :unchanged (:status outcome))
                        (empty? (select-keys outcome
                                             unchanged-module-forbidden-keys))))
                 modules)
         (nil? (:pending-generation runtime-status))
         (= intended-families observed-families)
         (= (set (keys current-roots)) observed-roots)
         (every? (fn [[family root :as family-root]]
                   (let [outcome (get-in families [family :roots root])
                         sync (:sync outcome)]
                     (and (contains? current-roots family-root)
                          (= :synced (:status outcome))
                          (map? sync)
                          (string? (:root sync))
                          (not (str/blank? (:root sync)))
                          (= (get current-roots family-root) (:root sync)))))
                 (keys current-roots)))))

(def negative-fixtures
  "Return unchanged evidence mutations that the proof must reject."
  (concat
   [{:fixture :extra-empty-family
     :proof (update-in refresh-fixture
                       [:pre-refresh-status :families]
                       assoc 'demo/extra-family {:roots {}})}
    {:fixture :missing-sync
     :proof (update-in refresh-fixture
                       [:pre-refresh-status :families 'demo/family :roots 'demo/root]
                       dissoc :sync)}
    {:fixture :non-map-sync
     :proof (assoc-in refresh-fixture
                      [:pre-refresh-status :families 'demo/family :roots 'demo/root :sync]
                      [])}
    {:fixture :missing-module-identity
     :proof (update-in refresh-fixture
                       [:refresh :modules 'demo/unchanged]
                       dissoc :module/key)}
    {:fixture :mismatched-module-identity
     :proof (assoc-in refresh-fixture
                      [:refresh :modules 'demo/unchanged :module/key]
                      'demo/other)}
    {:fixture :missing-root
     :proof (update-in refresh-fixture
                       [:pre-refresh-status :families 'demo/family :roots]
                       dissoc 'demo/root)}
    {:fixture :extra-root
     :proof (assoc-in refresh-fixture
                      [:pre-refresh-status :families 'demo/family :roots 'demo/extra]
                      (get-in refresh-fixture
                              [:pre-refresh-status :families 'demo/family :roots
                               'demo/root]))}
    {:fixture :mismatched-root
     :proof (update-in refresh-fixture
                       [:pre-refresh-status :families 'demo/family :roots]
                       #(assoc (dissoc % 'demo/root) 'demo/other
                               (get % 'demo/root)))}
    {:fixture :non-synced-root
     :proof (assoc-in refresh-fixture
                      [:pre-refresh-status :families 'demo/family :roots 'demo/root :status]
                      :failed)}
    {:fixture :absent-sync-root
     :proof (update-in refresh-fixture
                       [:pre-refresh-status :families 'demo/family :roots 'demo/root :sync]
                       dissoc :root)}
    {:fixture :blank-sync-root
     :proof (assoc-in refresh-fixture
                      [:pre-refresh-status :families 'demo/family :roots 'demo/root :sync :root]
                      "")}
    {:fixture :nonempty-invalid-sync-root
     :proof (assoc-in refresh-fixture
                      [:pre-refresh-status :families 'demo/family :roots 'demo/root :sync :root]
                      "/tmp/not-active")}
    {:fixture :mismatched-recorded-current-root
     :proof (assoc-in refresh-fixture
                      [:current-roots ['demo/family 'demo/root]]
                      "/tmp/not-active")}
    {:fixture :missing-recorded-current-root
     :proof (update refresh-fixture :current-roots dissoc
                    ['demo/family 'demo/root])}]
   (for [field unchanged-module-forbidden-keys]
     {:fixture (keyword (str "forbidden-module-" (name field)))
      :proof (update-in refresh-fixture
                        [:refresh :modules 'demo/unchanged]
                        assoc field true)})))

(def pending-fixture
  "Return accepted current, changed, prepared, and pending root evidence."
  (let [changed-roots [{:lib 'demo/root
                        :previous-root "/tmp/demo-v1"
                        :new-root "/tmp/demo-v2"}]
        diff {:changed-roots changed-roots
              :namespace-residuals []}]
    {:current-roots {['demo/family 'demo/root] "/tmp/demo-v1"
                     ['demo/family 'demo/unchanged] "/tmp/unchanged"}
     :changed-roots changed-roots
     :prepared-roots {['demo/family 'demo/root] "/tmp/demo-v2"}
     :runtime-status
     {:pending-generation {:status :pending
                           :generation "generation-2"
                           :diff diff
                           :approved-spools #{'demo/root}
                           :remedy "takes effect at the next generation"}}}))

(defn pending-valid?
  "Return whether pending roots reconcile with recorded pre-refresh roots."
  [{:keys [current-roots changed-roots prepared-roots runtime-status]}]
  (let [pending (:pending-generation runtime-status)
        changed-libs (mapv :lib changed-roots)
        changed-family-roots
        (mapv (fn [lib]
                (let [matches (filterv #(= lib (second %))
                                       (keys current-roots))]
                  (when (= 1 (count matches))
                    (first matches))))
              changed-libs)]
    (and (map? current-roots)
         (seq current-roots)
         (every? #(and (string? %) (not (str/blank? %)))
                 (vals current-roots))
         (vector? changed-roots)
         (seq changed-roots)
         (every? #(= #{:lib :previous-root :new-root} (set (keys %)))
                 changed-roots)
         (every? (fn [{:keys [previous-root new-root]}]
                   (and (string? previous-root)
                        (not (str/blank? previous-root))
                        (string? new-root)
                        (not (str/blank? new-root))))
                 changed-roots)
         (map? prepared-roots)
         (every? #(and (string? %) (not (str/blank? %)))
                 (vals prepared-roots))
         (= (count changed-libs) (count (set changed-libs)))
         (every? some? changed-family-roots)
         (= (set changed-family-roots) (set (keys prepared-roots)))
         (every? (fn [[family-root changed]]
                   (and (= (get current-roots family-root)
                           (:previous-root changed))
                        (= (get prepared-roots family-root)
                           (:new-root changed))))
                 (map vector changed-family-roots changed-roots))
         (= #{:status :generation :diff :approved-spools :remedy}
            (set (keys pending)))
         (= :pending (:status pending))
         (= #{:changed-roots :namespace-residuals}
            (set (keys (:diff pending))))
         (= changed-roots (get-in pending [:diff :changed-roots])))))

(def pending-negative-fixtures
  "Return pending root mutations that exact reconciliation must reject."
  [{:fixture :mismatched-previous-root
    :proof (assoc-in pending-fixture [:changed-roots 0 :previous-root]
                     "/tmp/not-current")}
   {:fixture :mismatched-prepared-root
    :proof (assoc-in pending-fixture
                     [:prepared-roots ['demo/family 'demo/root]]
                     "/tmp/not-prepared")}
   {:fixture :extra-prepared-root
    :proof (assoc-in pending-fixture
                     [:prepared-roots ['demo/family 'demo/extra]]
                     "/tmp/extra")}
   {:fixture :missing-current-root
    :proof (update pending-fixture :current-roots dissoc
                   ['demo/family 'demo/root])}
   {:fixture :blank-current-root
    :proof (assoc-in pending-fixture
                     [:current-roots ['demo/family 'demo/root]]
                     " ")}
   {:fixture :blank-prepared-root
    :proof (assoc-in pending-fixture
                     [:prepared-roots ['demo/family 'demo/root]]
                     " ")}
   {:fixture :mismatched-runtime-diff
    :proof (assoc-in pending-fixture
                     [:runtime-status :pending-generation :diff :changed-roots 0
                      :new-root]
                     "/tmp/not-prepared")}
   {:fixture :extra-pending-key
    :proof (assoc-in pending-fixture
                     [:runtime-status :pending-generation :unexpected]
                     true)}])
