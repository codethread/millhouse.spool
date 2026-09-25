(ns millhouse.config.agents
  "Register Millhouse's shared Harnesses seats and routing policy.

  The aliases follow the authoritative Harnesses workspace catalog while
  retaining Millhouse's shared routing policy."
  (:require [millhouse.harnesses :as harnesses]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.lifecycle.alpha :as lifecycle]))

(def allow-china-flag
  "Runtime flag permitting DeepSeek-powered seats; defaults true.

  Alias conditions read an unset flag as false, so `open-shared-catalog!`
  writes the true default. The `:deepseek` seat is gated on it, so setting it
  false makes that seat unavailable and routes `:grunt` to its Luna fallback:

  ```text
  strand agent config set seat/allow-china false
  ```
  "
  :seat/allow-china)

(def ^:private alias-definitions
  {:deepseek
   {:doc (format-alpha/prose
          "
            Scores: complexity 6; code-taste 7; resilience 6; ui-design X; cost 9.
            deepseek-v4-flash

            Strong coding model that works fast and effectively against well-scoped
            acceptance criteria. Very fast and cheap; favour it when work is well
            understood or has clear precedent.

            Unavailable while `seat/allow-china` is false.
            "
          {})
    :parent :pi
    :model "deepseek/deepseek-v4-flash"
    :effort :max
    :when allow-china-flag
    :allow #{:reviewer :oracle}
    :attributes {}}

   :luna
   {:doc (format-alpha/prose
          "
            Scores: complexity 3; code-taste 4; resilience 1; ui-design 2; cost 9.

            gpt-5.6-luna for implementation details, scouting, and tightly
            scoped delegated tasks. Be explicit about success criteria.
            "
          {})
    :parent :pi
    :model "openai-codex/gpt-5.6-luna"
    :effort :xhigh
    :allow #{:reviewer :oracle}
    :attributes {}}

   :opus
   {:doc (format-alpha/prose
          "
            Scores: complexity 8; code-taste 9; resilience X; ui-design 9;
            coordination 6; docs-prose 7; cost 2.

            Claude Opus for greenfield features, API design, and critical
            seams. Keep independent review for authored changes.
            "
          {})
    :parent :claude
    :model "opus"
    :effort :low
    :attributes {}}

   :fable
   {:doc (format-alpha/prose
          "
            Scores: complexity 9; code-taste 9; resilience X; ui-design 8;
            coordination 9; docs-prose 9; cost 1.

            Claude Fable for extreme diagnosis, top-level coordination, and
            user-facing prose where writing is the product.
            "
          {})
    :parent :claude
    :model "claude-fable-5"
    :effort :high
    :attributes {}}

   :astra
   {:doc (format-alpha/prose
          "
            gpt-6-astra for extreme diagnosis, top-level coordination,
            architectural guidance, and high-stakes user-facing prose.
            "
          {})
    :parent :pi
    :model "openai-codex/gpt-6-astra"
    :effort :high
    :attributes {}}

   :sol
   {:doc (format-alpha/prose
          "
            Scores: complexity 7; code-taste 6; resilience 9; ui-design 5;
            coordination 8; cost 5.

            gpt-5.6-sol for complex implementation, hostile-environment
            debugging, and delegated coordination.
            "
          {})
    :parent :pi
    :model "openai-codex/gpt-5.6-sol"
    :effort :low
    :attributes {}}

   :terra
   {:doc (format-alpha/prose
          "
            Scores: complexity 5; code-taste 5; resilience 2; ui-design 4;
            coordination 5; cost 7.

            gpt-5.6-terra high for well-defined single-concern review and
            validation on clean checkouts.
            "
          {})
    :parent :pi
    :model "openai-codex/gpt-5.6-terra"
    :effort :high
    :attributes {}}

   :grok
   {:doc "Grok for greenfield implementation, API design, and critical code review."
    :parent :cursor
    :model "cursor-grok-4.6"
    :effort :high
    :attributes {:harness.cursor/fast true}}

   :oracle
   {:doc "Default seat for high-stakes guidance, diagnosis, and architecture."
    :parent :astra
    :effort :high
    :attributes {}}

   :reviewer
   {:doc "Default seat for targeted reviews."
    :parent :terra
    :allow #{:grunt :oracle}
    :attributes {}}

   :grunt
   [{:doc (format-alpha/prose
           "
             Preferred seat for mechanical, tightly scoped implementation
             tasks, exploration work, large file/log searching and other
             context heavy search tasks
             "
           {})
     :parent :deepseek
     :allow #{:reviewer :oracle}
     :attributes {}}
    {:doc (format-alpha/prose
           "
             Preferred seat for mechanical, tightly scoped implementation
             tasks, exploration work, large file/log searching and other
             context heavy search tasks
             "
           {})
     :parent :luna
     :allow #{:reviewer :oracle}
     :attributes {}}]

   :tui
   {:doc "Primary interactive user seat."
    :parent :sol
    :effort :low
    :attributes {}}})

(defn- apply-flag-default!
  "Set `flag` to `value` unless this runtime already has an override."
  [runtime flag value]
  (when (nil? (harnesses/flag runtime flag))
    (harnesses/set-flag! runtime flag value)))

(defn open-shared-catalog!
  "Register shared aliases and apply the default provider policy.

  Claude and Cursor remain registered but disabled. Consumers may explicitly
  enable either process-local flag after startup.

  `seat/allow-china` is defaulted rather than forced, so an operator who
  already overrode it keeps that decision across a catalog reopen."
  [{:keys [runtime]}]
  (doseq [provider-flag [:harness/claude :harness/cursor]]
    (harnesses/set-flag! runtime provider-flag false))
  (apply-flag-default! runtime allow-china-flag true)
  (let [registrations
        (mapv (fn [[alias descriptor]]
                (harnesses/register-alias! runtime alias descriptor))
              alias-definitions)]
    {:opened :millhouse/shared-catalog
     :aliases (mapv :alias registrations)}))

(defn close-shared-catalog!
  "Remove aliases owned by the shared catalog resource."
  [{:keys [runtime resource]}]
  (doseq [alias (:aliases resource)]
    (harnesses/unregister-alias! runtime alias))
  {:closed :millhouse/shared-catalog})

(lifecycle/defresource! shared-harness-catalog
  "Own Millhouse's shared Harnesses aliases and provider defaults."
  {:open 'millhouse.config.agents/open-shared-catalog!
   :close 'millhouse.config.agents/close-shared-catalog!})
