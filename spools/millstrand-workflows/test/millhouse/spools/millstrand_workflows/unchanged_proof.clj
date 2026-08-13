(ns millhouse.spools.millstrand-workflows.unchanged-proof
  "Shared fixtures for the unchanged refresh proof suites."
  (:require [clojure.string :as str]))

(def refresh-fixture
  "Return the accepted unchanged refresh and spool-status evidence fixture."
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
   :active-roots {['demo/family 'demo/root] "/tmp/demo"}
   :spool-status {:families
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
  "Return whether unchanged refresh evidence covers its active roots exactly."
  [{:keys [refresh runtime-status spool-status active-roots]}]
  (let [modules (:modules refresh)
        families (:families spool-status)
        intended-families (set (map first (keys active-roots)))
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
         (= (set (keys active-roots)) observed-roots)
         (every? (fn [[family root :as family-root]]
                   (let [outcome (get-in families [family :roots root])
                         sync (:sync outcome)]
                     (and (contains? active-roots family-root)
                          (= :synced (:status outcome))
                          (map? sync)
                          (string? (:root sync))
                          (not (str/blank? (:root sync)))
                          (= (get active-roots family-root) (:root sync)))))
                 (keys active-roots)))))

(def negative-fixtures
  "Return unchanged evidence mutations that the proof must reject."
  (concat
   [{:fixture :extra-empty-family
     :proof (update-in refresh-fixture
                       [:spool-status :families]
                       assoc 'demo/extra-family {:roots {}})}
    {:fixture :missing-sync
     :proof (update-in refresh-fixture
                       [:spool-status :families 'demo/family :roots 'demo/root]
                       dissoc :sync)}
    {:fixture :non-map-sync
     :proof (assoc-in refresh-fixture
                      [:spool-status :families 'demo/family :roots 'demo/root :sync]
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
                       [:spool-status :families 'demo/family :roots]
                       dissoc 'demo/root)}
    {:fixture :extra-root
     :proof (assoc-in refresh-fixture
                      [:spool-status :families 'demo/family :roots 'demo/extra]
                      (get-in refresh-fixture
                              [:spool-status :families 'demo/family :roots
                               'demo/root]))}
    {:fixture :mismatched-root
     :proof (update-in refresh-fixture
                       [:spool-status :families 'demo/family :roots]
                       #(assoc (dissoc % 'demo/root) 'demo/other
                               (get % 'demo/root)))}
    {:fixture :non-synced-root
     :proof (assoc-in refresh-fixture
                      [:spool-status :families 'demo/family :roots 'demo/root :status]
                      :failed)}
    {:fixture :absent-sync-root
     :proof (update-in refresh-fixture
                       [:spool-status :families 'demo/family :roots 'demo/root :sync]
                       dissoc :root)}
    {:fixture :blank-sync-root
     :proof (assoc-in refresh-fixture
                      [:spool-status :families 'demo/family :roots 'demo/root :sync :root]
                      "")}
    {:fixture :nonempty-invalid-sync-root
     :proof (assoc-in refresh-fixture
                      [:spool-status :families 'demo/family :roots 'demo/root :sync :root]
                      "/tmp/not-active")}]
   (for [field unchanged-module-forbidden-keys]
     {:fixture (keyword (str "forbidden-module-" (name field)))
      :proof (update-in refresh-fixture
                        [:refresh :modules 'demo/unchanged]
                        assoc field true)})))
