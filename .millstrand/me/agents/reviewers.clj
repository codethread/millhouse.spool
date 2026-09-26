(ns me.agents.reviewers
  "Declare Millhouse's repository-specific test-layer review lens."
  (:require [millhouse.harnesses :as harnesses]
            [millhouse.harnesses.reviewers :as reviewers]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.lifecycle.alpha :as lifecycle]))

(defn open-test-layer-seat!
  "Register the direct-provider DeepSeek max seat for test-layer review."
  [{:keys [runtime]}]
  ;; Inherit the shared DeepSeek availability policy, but name the direct
  ;; provider's actual model rather than OpenRouter's similarly named model.
  (harnesses/register-alias!
   runtime :test-layer-reviewer
   {:doc "Review test-layer selection with direct-provider DeepSeek Flash max."
    :parent :deepseek
    :model "deepseek/deepseek-flash"
    :effort :max
    :attributes {}}))

(defn close-test-layer-seat!
  "Remove the repository-owned test-layer reviewer seat."
  [{:keys [runtime]}]
  (harnesses/unregister-alias! runtime :test-layer-reviewer))

(lifecycle/defresource! test-layer-seat
  "Own the repository-specific test-layer reviewer seat."
  {:open 'me.agents.reviewers/open-test-layer-seat!
   :close 'me.agents.reviewers/close-test-layer-seat!})

(reviewers/defreviewer!
  test-layering
  "Check that changed tests use the cheapest layer that proves their contract."
  {:seat 'test-layer-reviewer
   :labels ["PR" "Tests" "Test-layering"]
   :glob ["test/**" "spools/*/test/**" "spools/*/*/test/**"
          ".millstrand/test/**"]}
  (format-alpha/prose
   "
    Review test-layer selection, not general correctness, style, or coverage.
    Read `.agents/skills/testing/SKILL.md` in the reviewed checkout. Use the
    standard testing pyramid: choose the cheapest layer that still proves the
    behavior asserted, not the cheapest fixture regardless of evidence.

    Review the complete changed tests and changed fixtures, including existing
    setup or assertions within those tests. Do not excuse a mismatch because
    it predates the diff. Read supporting code to understand the contract, but
    do not request refactors to other files or untouched tests. A changed
    assertion can justify simplifying its existing fixture. Do not expand a
    small patch into a suite-wide cleanup.

    Trace what the assertions actually observe and what their fixture starts.
    Pure logic/declaration data needs no Weaver. Direct API contracts do not
    automatically require startup/dependency-resolution evidence. Embedded
    worlds are appropriate for startup, publication, transport, events,
    scheduling, and reload. Separate processes are required for replacement-
    generation adoption. An embedded restart does not prove process cutover.
    When evidence is insufficient, preserve the stated contract and recommend
    the sufficient tier, not weakening the claim to fit the existing fixture.
    Do not add a competing storage downgrade that would defeat that outcome.

    Storage is an independent choice, not a test tier. Real SQLite memory
    storage holds one connection and is appropriate for serialized contracts
    that do not require persistence, files, or multiple connections. Retain
    file storage for durability/reopen and connection-topology evidence.
    Memory worlds still construct dependency bases. Never recommend mocking
    away the behavior under test or dropping necessary integration evidence.

    Verify APIs and fixtures before claiming a cheaper supported alternative.
    Millhouse's `with-runtime` currently creates a full world; it is not a
    cheap direct fixture. `activate-module!` requires an existing runtime.
    If a cheaper runtime constructor is unavailable, state that limitation
    rather than inventing an API or importing upstream private helpers.

    Report only concrete, actionable layer/storage mismatches. For each, give
    a repository-relative path and line, the behavior being proved, why the
    current setup is excessive or insufficient, and a brief recommended
    outcome at the right layer. Each finding is one short paragraph of at
    most three sentences. Output findings only: no scope notes, inventories
    of correct tests, code snippets, helper architecture, or implementation
    plan. Do not flag a full world or file database merely because it exists.
    Say `No findings` when the selected layer and storage are justified.
    Do not edit files, run the suite, or mutate repository/runtime state.
  " {}))
