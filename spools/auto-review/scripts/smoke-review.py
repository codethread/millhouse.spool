"""Exercise real CLI, lifecycle, Git worktrees, Harnesses processes and first-class review evidence.

Only the GitLab executable and reviewer provider are fakes. The caller supplies
a disposable mktemp workspace, and the shell wrapper stops and removes it.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time


parser = argparse.ArgumentParser()
parser.add_argument("--workspace", required=True)
parser.add_argument("--keep-open", action="store_true",
                    help="leave the disposable Weaver and review resource open for UI inspection")
args = parser.parse_args()
workspace = Path(args.workspace).resolve()
checkout = Path(__file__).resolve().parents[1]
source = workspace / "source"
origin = workspace / "origin.git"
reviews = workspace / "reviews"
teardown_events = workspace / "teardown-events"


def run(argv, cwd=None):
    result = subprocess.run(argv, cwd=cwd, text=True, capture_output=True, timeout=120)
    if result.returncode:
        raise RuntimeError(f"{argv}: {result.stdout}\n{result.stderr}")
    return result.stdout.strip()


def git(*argv, cwd=source):
    return run(["git", *argv], cwd)


def strand(*argv):
    return json.loads(run(["strand", "--workspace", str(workspace), *argv]))


def strand_payload(request, *argv):
    payload = workspace / "request.json"
    payload.write_text(json.dumps(request))
    return json.loads(run(["strand", "--workspace", str(workspace),
                           "--payload", f"request={payload}", *argv,
                           "--request", ":payload/request"]))


def strand_failure(*argv):
    result = subprocess.run(["strand", "--workspace", str(workspace), *argv],
                            text=True, capture_output=True, timeout=120)
    assert result.returncode, f"Expected command failure: {argv}"
    return result.stdout + result.stderr


def review_logs(*argv):
    # Real stream operation must emit JSON objects per line, not a quoted string.
    output = run(["strand", "--workspace", str(workspace), "review-logs", *argv])
    entries = [json.loads(line) for line in output.splitlines()]
    assert all(isinstance(entry, dict) for entry in entries)
    return entries


def repl(source):
    result = subprocess.run(
        ["mill", "weaver", "repl", "--workspace", str(workspace), "--stdin"],
        input=source, text=True, capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def wait_for(predicate, description):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        status = strand("review", "status")
        if not status["busy"] and status.get("last-error"):
            raise AssertionError(f"Review worker failed: {status}")
        if not status["busy"] and any(c["attributes"]["mr-review/stage"] in ("failed", "dispatching")
                                      for c in status["reviews"]):
            raise AssertionError(str([strand("review", "show", c["id"]) for c in status["reviews"]]))
        time.sleep(0.2)
    raise AssertionError(f"Timed out: {description}\n{strand('review', 'status')}")


source.mkdir()
git("init", "-b", "develop")
git("config", "user.email", "fixture@example.invalid")
git("config", "user.name", "Review Fixture")
(source / ".gitignore").write_text(".millstrand\ndependencies/\n")
(source / "app.clj").write_text("(def answer 1)\n")
git("add", ".")
git("commit", "-m", "base")
base = git("rev-parse", "HEAD")
git("switch", "-c", "mr")
(source / "app.clj").write_text("(def answer 2)\n")
git("commit", "-am", "MR revision one")
head = git("rev-parse", "HEAD")
git("update-ref", "refs/merge-requests/1/head", head)
git("clone", "--bare", str(source), str(origin))
git("update-ref", "refs/merge-requests/1/head", head, cwd=origin)
git("remote", "add", "origin", str(origin))
git("switch", "develop")
(source / "app.clj").write_text("canonical dirty work must survive\n")
(source / "untracked.txt").write_text("not part of MR\n")
(source / "dependencies").mkdir()
(source / "dependencies/ignored.txt").write_text("not part of MR\n")
protected = workspace / "protected-world"
protected.mkdir()
(protected / "sentinel").write_text("untouched")
(source / ".millstrand").symlink_to(protected, target_is_directory=True)
before = [git("branch", "--show-current"), git("diff", "--binary", "HEAD"), git("status", "--porcelain")]


def publish(sha, pipeline_status="success"):
    mr = {"iid": 1, "project_id": 7, "sha": sha, "state": "opened", "draft": False,
          "title": "Fixture MR", "labels": [], "web_url": "https://example.invalid/project/-/merge_requests/1",
          "diff_refs": {"head_sha": sha, "base_sha": base, "start_sha": base},
          "head_pipeline": {"id": 100, "sha": sha, "status": pipeline_status},
          "pipeline": {"id": 99, "sha": sha, "status": "success"}}
    (workspace / "mrs.json").write_text(json.dumps([mr, dict(mr, iid=2, draft=True)]))
    (workspace / "mr.json").write_text(json.dumps(mr))


publish(head, "running")
fake_glab = workspace / "fake-glab"
fake_glab.write_text('''#!/usr/bin/env python3
import hashlib, json, pathlib, sys
root = pathlib.Path(__file__).parent
args = sys.argv[1:]
with (root / "glab-calls.jsonl").open("a") as log: log.write(json.dumps(args) + "\\n")
if args[:2] == ["mr", "list"]:
    print((root / "mrs.json").read_text())
elif args[:2] == ["mr", "view"]:
    print((root / "mr.json").read_text())
elif args == ["api", "user"]:
    print(json.dumps({"id": 42, "username": "fixture-user"}))
elif args[:1] == ["api"] and "/versions?" in args[1]:
    mr = json.loads((root / "mr.json").read_text())
    refs = mr["diff_refs"]
    print(json.dumps([{"base_commit_sha": refs["base_sha"],
                       "start_commit_sha": refs["start_sha"],
                       "head_commit_sha": refs["head_sha"]}]))
elif args[:1] == ["api"] and "/diffs?" in args[1]:
    print(json.dumps([{"old_path": "app.clj", "new_path": "app.clj",
                       "diff": "@@ -1 +1 @@\\n-(def answer 1)\\n+(def answer 2)"}]))
elif args[:1] == ["api"] and "/discussions?" in args[1]:
    path = root / "discussions.json"
    print(path.read_text() if path.exists() else "[]")
elif args[:3] == ["api", "--method", "POST"]:
    path = root / "discussions.json"
    rows = json.loads(path.read_text()) if path.exists() else []
    fields = [args[index + 1] for index, value in enumerate(args[:-1]) if value == "--raw-field"]
    body = next(value[5:] for value in fields if value.startswith("body="))
    discussion = {"id": f"discussion-{len(rows) + 1}", "notes": [{"body": body}]}
    rows.append(discussion)
    path.write_text(json.dumps(rows))
    print(json.dumps(discussion))
    sentinel = root / ("ambiguous-post-" + hashlib.sha1(body.encode()).hexdigest())
    if "General fixture finding" in body and not sentinel.exists():
        sentinel.write_text("stored remotely before simulated transport failure\\n")
        raise SystemExit(70)
else:
    raise SystemExit("Forbidden GitLab operation: " + repr(args))
''')
fake_glab.chmod(0o755)
(workspace / "deps.edn").write_text('''{:paths ["."]
 :deps {millhouse.spools/auto-review {:local/root ''' + json.dumps(str(checkout)) + '''}
        codethread/config
        {:git/url "https://github.com/codethread/codethread.spool.git"
         :git/sha "b28c42d086f47a12c66fa42159b24df8890f8166"
         :deps/root "spools/config"}
        millhouse.spools/identity
        {:git/url "https://github.com/codethread/millhouse.spool.git"
         :git/sha "62723b7b1820c7e1723de4a2ff985b069871159e"
         :deps/root "spools/identity"}}}''')
(workspace / "config.json").write_text('{"configFormat":"alpha","autoStart":false}')
init = '''(require '[ct.spools.codethread.bootstrap :as codethread]
                 '[millstrand.api.current.alpha :as current]
                 '[millstrand.api.runtime.alpha :as runtime])
(def runtime (current/runtime))
(codethread/register! runtime)
(runtime/module! runtime :review
  {:ns 'millhouse.spools.auto-review :after [:codethread/config-reviewers] :required? true})
(runtime/module! runtime :review-fixture
  {:file "fixture.clj" :after [:review] :required? true})
(codethread/register-executor! runtime [:review-fixture])
'''
(workspace / "init.clj").write_text(init)
fixture = '''(ns fixture
 (:require [clojure.data.json :as json]
           [ct.spools.harnesses :as harnesses]
           [ct.spools.harnesses.reviewers :as reviewers]
           [millhouse.spools.auto-review :as review]
           [millstrand.api.lifecycle.alpha :as lifecycle]
           [millstrand.api.spool.alpha :refer [attr-get]]))
(defn prepare [_rt _definition run]
 (when (.exists (clojure.java.io/file (str (attr-get run :harness/cwd) "/dependencies/ready")))
   (assert (= (str (attr-get run :harness/cwd) "\n")
              (slurp (str (attr-get run :harness/cwd) "/dependencies/ready")))))
 (let [reviewer (name (attr-get run :harness/alias))]
  {:argv ["/bin/sh" "-ec" "test -f app.clj; test -z \\\"$(git status --porcelain --untracked-files=no)\\\"; sleep 0.2; printf '%s' \\\"$1\\\"" "fixture"
          (json/write-str
           {:summary (str "Structured fixture summary from " reviewer)
            :comments [{:title "Changed answer" :text (str "Line fixture finding from " reviewer)
                        :severity "P2"
                        :position {:kind "line" :oldPath "app.clj" :newPath "app.clj"
                                   :side "new" :line 1}}
                       {:title "General observation" :text (str "General fixture finding from " reviewer)
                        :position {:kind "general" :reason "Applies to the whole merge request"}}]})]
  :stdin nil}))
(defn finish [_rt _definition _run result]
 {:status :done :exit-code (:exit-code result) :result (:stdout result) :session-usable false})
(reviewers/defreviewer! proof-correctness "Fixture correctness reviewer." {:seat 'proof-seat} "Review correctness.")
(reviewers/defreviewer! proof-tests "Fixture tests reviewer." {:seat 'proof-seat} "Review tests.")
(def config CONFIG)
(defn open! [ctx]
 (harnesses/register-harness! (:runtime ctx) :proof-provider
  {:modes #{:headless} :prepare 'fixture/prepare :finish 'fixture/finish})
 (harnesses/register-alias! (:runtime ctx) :proof-seat
  {:doc "Local deterministic test provider." :parent :proof-provider :attributes {}})
 (review/open! ctx config))
(defn close! [ctx] (review/close! ctx))
(lifecycle/defresource! proof-review-runtime "Disposable review fixture."
 {:open 'fixture/open! :close 'fixture/close!})
'''


def setup_script(extra=""):
    return f'''git -C "$MILLSTRAND_REVIEW_REPO" fetch --no-tags --no-write-fetch-head origin "$MILLSTRAND_REVIEW_HEAD" "$MILLSTRAND_REVIEW_BASE"
worktree={json.dumps(str(reviews))}/$MILLSTRAND_REVIEW_ID
branch=review/$MILLSTRAND_REVIEW_ID
git -C "$MILLSTRAND_REVIEW_REPO" worktree add -b "$branch" "$worktree" "$MILLSTRAND_REVIEW_HEAD"
{extra}
export MILLSTRAND_REVIEW_WORKTREE_RESULT="$worktree" MILLSTRAND_REVIEW_BRANCH_RESULT="$branch"
python3 -c 'import json,os; json.dump({{"kind":"ready","worktree_path":os.environ["MILLSTRAND_REVIEW_WORKTREE_RESULT"],"branch":os.environ["MILLSTRAND_REVIEW_BRANCH_RESULT"]}}, open(os.environ["MILLSTRAND_REVIEW_RESULT"], "w"))'
'''


teardown_script = f'''printf '%s\\n' "$MILLSTRAND_REVIEW_ID" >> {json.dumps(str(teardown_events))}
if [ -n "$MILLSTRAND_REVIEW_WORKTREE" ] && [ -d "$MILLSTRAND_REVIEW_WORKTREE" ]; then
  git -C "$MILLSTRAND_REVIEW_REPO" worktree remove --force "$MILLSTRAND_REVIEW_WORKTREE"
fi
git -C "$MILLSTRAND_REVIEW_REPO" branch -D "review/$MILLSTRAND_REVIEW_ID" >/dev/null 2>&1 || true'''


config = ('{:repo-dir ' + json.dumps(str(source)) +
          ' :glab-bin ' + json.dumps(str(fake_glab)) +
          ' :reviewers ["proof-correctness" "proof-tests"] :poll? false :interval-seconds 300' +
          ' :setup ' + json.dumps(setup_script()) +
          ' :teardown ' + json.dumps(teardown_script) + '}')
(workspace / "fixture.clj").write_text(fixture.replace("CONFIG", config))
print("Starting disposable review fixture", flush=True)
run(["mill", "weaver", "start", "--workspace", str(workspace), "--json"])
assert strand("review", "status")["reviews"] == []
assert not (workspace / "glab-calls.jsonl").exists(), "Activation must not poll by default"
for status in ("running", "failed", None):
    publish(head, status)
    strand("review", "poll")
    wait_for(lambda: not strand("review", "status")["busy"], "waiting for aggregate pipeline success")
    assert strand("review", "status")["reviews"] == []
    assert len(strand("agent", "runs")) == 0
    assert not reviews.exists()
waiting_logs = review_logs("--mr", "1")
assert any(e.get("pipeline-status") == "running" and e.get("ci") == "observed" for e in waiting_logs)
assert any(e.get("pipeline-status") == "failed" for e in waiting_logs)
assert all(e["mr"] == 1 for e in waiting_logs)
assert review_logs("--mr", "98765") == []
assert len(review_logs("--limit", "2")) == 2
publish(head)
print("PASS: non-green latest pipeline consumes no slot; the same SHA becomes eligible when it passes", flush=True)
strand("review", "poll")
cards = wait_for(lambda: [c for c in strand("review", "status")["reviews"]
                          if c["attributes"]["mr-review/stage"] == "reviewed"], "review completion via event handler")
assert len(cards) == 1
card = cards[0]
view = strand("review", "show", card["id"])["review"]
assert view["decision"] == "pending" and view["reportAvailable"]
assert view["stage"] == "reviewed" and view["current"]
assert len(view["reviewers"]) == 2
assert all(p["status"] == "stopped" and p["substatus"] == "completed" for p in view["reviewers"])
assert all("Structured fixture summary" in p["summary"] for p in view["reviewers"])
assert all("result" not in p for p in view["reviewers"])
assert "## proof-correctness" in view["report"] and "## proof-tests" in view["report"]
assert view["report"].count("fixture finding") == 4
assert "mr-review/plans" not in json.dumps(view)
snapshot = strand("review", "comments", card["id"])
assert snapshot["review"]["revision"] == view["revision"]
assert snapshot["review"]["curation"] == {"version": 0, "mutable": True}
assert snapshot["review"]["mr"]["headSha"] == head
assert "sha" not in snapshot["review"]["mr"]
assert len(snapshot["comments"]) == 4
assert all(comment["candidate"]["version"] == 1 for comment in snapshot["comments"])
assert all(comment["candidate"]["source"] ==
           {"kind": "reviewer",
            "reviewer": comment["candidate"]["original"]["reviewer"],
            "runId": comment["candidate"]["original"]["runId"]}
           for comment in snapshot["comments"])
test_comments = [comment for comment in snapshot["comments"]
                 if comment["candidate"]["original"]["reviewer"] == "proof-tests"]
general = next(comment for comment in snapshot["comments"]
               if comment["candidate"]["original"]["reviewer"] == "proof-correctness"
               and comment["position"]["kind"] == "general")
curation = {
    "revision": view["revision"], "expectedVersion": 0, "by": "fixture-user",
    "changes": ([{"id": comment["id"], "inclusion": "dismissed"}
                 for comment in test_comments] +
                [{"id": general["id"],
                  "candidate": {"expectedVersion": 1,
                                "text": general["candidate"]["text"] + " (curated)"}}])}
curated = strand_payload(curation, "review", "curate", card["id"])
assert curated["review"]["curation"] == {"version": 1, "mutable": True}
curated_general = next(comment for comment in curated["comments"] if comment["id"] == general["id"])
assert curated_general["candidate"]["version"] == 2
assert curated_general["candidate"]["source"]["kind"] == "user-adopted"
assert curated_general["candidate"]["source"]["by"] == "fixture-user"
assert curated_general["candidate"]["original"] == general["candidate"]["original"]
stale = strand_failure("review", "curate", card["id"], "--request", json.dumps(curation))
assert "stale" in stale.lower()
pointer = {"revision": view["revision"], "curationVersion": 1}
first_publish = strand_payload(pointer, "review", "publish", card["id"])
assert first_publish["state"] == "partial"
assert {receipt["state"] for receipt in first_publish["comments"]} == {"published", "reconciling"}
posts_after_first = sum(1 for call in (workspace / "glab-calls.jsonl").read_text().splitlines()
                        if json.loads(call)[:3] == ["api", "--method", "POST"])
second_publish = strand_payload(pointer, "review", "publish", card["id"])
assert second_publish["state"] == "published"
assert all(receipt["state"] == "published" for receipt in second_publish["comments"])
posts_after_retry = sum(1 for call in (workspace / "glab-calls.jsonl").read_text().splitlines()
                        if json.loads(call)[:3] == ["api", "--method", "POST"])
assert posts_after_retry == posts_after_first == 2, "marker reconciliation must not duplicate POSTs"
published = strand("review", "comments", card["id"])
assert published["review"]["publication"]["state"] == "published"
assert not published["review"]["curation"]["mutable"]
assert len(strand("review", "list")["reviews"]) == 1
assert strand("review", "list", "--mr", "98765")["reviews"] == []
assert strand("review", "list", "--stage", "failed")["reviews"] == []
assert card["id"] not in json.dumps(strand("kanban", "board"))
work = strand("kanban", "add", "Follow up review findings")["card"]["id"]
linked = strand("review", "link", card["id"], work)["review"]
assert linked["links"] == [{"id": work, "title": "Follow up review findings", "type": "review-of"}]
worktree = Path(card["attributes"]["worktree"])
assert worktree.exists(), "publishing must not finish or tear down the review"
assert not teardown_events.exists(), "publishing must not invoke teardown"
assert git("rev-parse", "HEAD", cwd=worktree) == head
assert (worktree / "app.clj").read_text() == "(def answer 2)\n"
assert not (worktree / ".millstrand").exists()
assert not (worktree / "dependencies").exists()
assert not (worktree / "untracked.txt").exists()
assert before == [git("branch", "--show-current"), git("diff", "--binary", "HEAD"), git("status", "--porcelain")]
assert (protected / "sentinel").read_text() == "untouched"
run_count = len(strand("agent", "runs"))
strand("review", "poll")
wait_for(lambda: not strand("review", "status")["busy"], "deduplicated poll")
strand("review", "reconcile")
wait_for(lambda: not strand("review", "status")["busy"], "repeat completion reconciliation")
assert len(strand("agent", "runs")) == run_count == 2
assert len(strand("review", "status")["reviews"]) == 1
print("PASS: structured comments, durable curation CAS, partial publication, marker reconciliation, retry, and no implicit finish", flush=True)
print("PASS: two real fixture processes, exact SHA, derived report, reviewer evidence and local inbox, and unchanged-MR dedup", flush=True)

# Simulate the crash window after launches committed but before run IDs were
# linked to the card. The persisted frozen CLI request IDs must recover them.
repl(f'''(require '[millstrand.api.weaver.alpha :as w]
                  '[millstrand.api.current.alpha :as c])
 (w/update! (c/runtime) "{card['id']}"
  {{:attributes {{:mr-review/stage "dispatching" :mr-review/runs nil}}}})''')
strand("review", "reconcile")
wait_for(lambda: not strand("review", "status")["busy"], "dispatch recovery")
assert len(strand("agent", "runs")) == 2
print("PASS: partial dispatch recovery reused both existing agent requests", flush=True)

# Create a second commit in a disposable worktree, then move the fixture MR ref.
(worktree / "app.clj").write_text("(def answer 3)\n")
# Large generated source must not be materialized into the prompt. Reviewers
# choose which changed paths to inspect.
(worktree / "generated.txt").write_text("GENERATED-CONTEXT-SENTINEL\n" * 50000)
git("add", "generated.txt", cwd=worktree)
git("commit", "-am", "MR revision two", cwd=worktree)
new_head = git("rev-parse", "HEAD", cwd=worktree)
git("push", str(origin), f"{new_head}:refs/merge-requests/1/head", cwd=worktree)
publish(new_head)
# Exercise the real scheduler wake path, without enabling a continuous poller.
setup_with_dependencies = setup_script(
    'mkdir -p "$worktree/dependencies"; printf \'%s\\n\' "$worktree" > "$worktree/dependencies/ready"')
repl(f'''(require '[millstrand.api.scheduler.alpha :as scheduler]
                 '[millstrand.api.runtime.alpha :as runtime]
                 '[millhouse.spools.auto-review :as review])
 (review/close! {{:runtime (c/runtime)}})
 (review/open! {{:runtime (c/runtime)}}
   (assoc fixture/config :poll? true :interval-seconds 2
          :setup {json.dumps(setup_with_dependencies)}))''')
wait_for(lambda: len([c for c in strand("review", "status")["reviews"]
                      if c["attributes"]["mr-review/stage"] == "reviewed"]) == 2, "new SHA via scheduler")
assert len(strand("agent", "runs")) == 4
cards = strand("review", "status")["reviews"]
assert {c["attributes"]["mr-review/current"] for c in cards} == {"true", "false"}
prepared = next(c for c in cards if c["id"] != card["id"])
prepared_tree = Path(prepared["attributes"]["worktree"])
assert (prepared_tree / "dependencies/ready").read_text().strip() == str(prepared_tree)
repl(f'''(let [plans (millhouse.spools.auto-review.internal.board/data (w/show (c/runtime) "{prepared['id']}") :mr-review/plans)]
 (assert (every? #(< (count (:prompt %)) 2000) plans))
 (assert (every? #(clojure.string/includes? (:prompt %) "setup completed") plans))
 (assert (not-any? #(clojure.string/includes? (:prompt %) "GENERATED-CONTEXT-SENTINEL") plans))
 (assert (every? #(= "{prepared_tree}" (:cwd %)) plans)))''')
assert any("setup completed" in n["text"] for n in strand("review", "show", prepared["id"])["review"]["notes"])
print("PASS: setup runs in new worktree; large generated patch stays out of prompts", flush=True)

# Both completed reports still await human decisions. Even a new head cannot
# consume another review until the user records a local review decision.
(worktree / "app.clj").write_text("(def answer 4)\n")
git("commit", "-am", "MR revision three", cwd=worktree)
third_head = git("rev-parse", "HEAD", cwd=worktree)
git("push", str(origin), f"{third_head}:refs/merge-requests/1/head", cwd=worktree)
publish(third_head)
strand("review", "poll")
wait_for(lambda: not strand("review", "status")["busy"], "capacity-limited poll")
assert len(strand("review", "status")["reviews"]) == 2
assert len(strand("agent", "runs")) == 4
assert any(e.get("reason") == "capacity" and e.get("ci") == "unknown"
           for e in review_logs("--mr", "1"))
finished = strand("review", "finish", card["id"], "--by", "fixture", "--note", "Assessed findings")["review"]
assert finished["decision"] == "done" and finished["state"] == "closed"
assert finished["teardownStatus"] == "done"
assert finished["notes"][-1]["text"] == "Assessed findings"
assert not worktree.exists(), "actioning the review must tear down its retained workspace"
assert teardown_events.read_text().splitlines() == [card["id"]]
assert strand("review", "finish", card["id"])["review"]["decision"] == "done"
assert teardown_events.read_text().splitlines() == [card["id"]], "repeated finish must not rerun teardown"
assert card["id"] not in [r["id"] for r in strand("review", "list")["reviews"]]
assert card["id"] in [r["id"] for r in strand("review", "list", "--all")["reviews"]]
wait_for(lambda: len([c for c in strand("review", "status")["reviews"]
                      if c["attributes"]["mr-review/stage"] == "reviewed"]) == 3, "human decision releases one slot")
assert len(strand("agent", "runs")) == 6
assert len([c for c in strand("review", "status")["reviews"] if c["state"] == "active"]) == 2
print("PASS: two finished reports block new reviews until a human records a local decision", flush=True)

repl('''(review/close! {:runtime (c/runtime)})
 (assert (empty? (filter #(contains? #{"millhouse.spools.auto-review/poll" "millhouse.spools.auto-review/prune-logs"} (:key %)) (scheduler/pending (c/runtime)))))''')
# Verify durable graph links, bounded reads and strictly scoped age pruning.
repl('''(require '[millhouse.spools.auto-review.internal.logs :as logs]
                 '[millstrand.api.spool.alpha :refer [attr-get]]
                 '[millstrand.api.batch.alpha :as batch])
 (let [rt (c/runtime) now (runtime/now rt)]
   (with-redefs [runtime/now (fn [_] (.minusSeconds now (* 86400 8)))]
     (logs/append! rt "fixture-expired" {}))
   (dotimes [_ 105] (logs/append! rt "fixture-recent" {}))
   (def expired-id (:id (first (w/list rt [:= [:attr "mr-review/log-json"]
     (clojure.data.json/write-str {:at (str (.minusSeconds now (* 86400 8))) :event "fixture-expired"})] {}))))
   (def unrelated-id (:id (w/add! rt {:title "Unrelated old event" :state "closed"
     :attributes {:mr-review/log-event "true" :mr-review/repo (:repo-dir fixture/config)
                  :mr-review/log-at (str (.minusSeconds now (* 86400 8)))}})))
   (def protected-logs
     (batch/apply! rt {:refs {:root (first (:roots (logs/status rt)))}
       :strands [{:ref :foreign :title "Other repository event" :state "closed"
                  :attributes {:mr-review/log-event "true" :mr-review/repo "/other/repo"
                               :mr-review/log-at (str (.minusSeconds now (* 86400 8)))}}
                 {:ref :impostor :title "Linked non-log strand" :state "closed"
                  :attributes {:mr-review/repo (:repo-dir fixture/config)
                               :mr-review/log-at (str (.minusSeconds now (* 86400 8)))}}]
       :edges [{:op :upsert :from :root :to :foreign :type "review-log"}
               {:op :upsert :from :root :to :impostor :type "review-log"}]}))
   (assert expired-id)
   (logs/prune! rt)
   (assert (nil? (w/show rt expired-id)))
   (assert (w/show rt unrelated-id))
   (assert (w/show rt (get-in protected-logs [:refs :foreign])))
   (assert (w/show rt (get-in protected-logs [:refs :impostor])))
   (assert (= 1 (count (:roots (logs/status rt)))))
   (review/open! {:runtime rt} fixture/config)
   (assert (some #(= "millhouse.spools.auto-review/prune-logs" (:key %)) (scheduler/pending rt)))
   (assert (not-any? #(= "millhouse.spools.auto-review/poll" (:key %)) (scheduler/pending rt)))
   (review/prune-wake! {:runtime rt}))''')
wait_for(lambda: not strand("review", "status")["busy"], "startup log pruning")
assert len(review_logs()) == 100
assert len(review_logs("--limit", "3")) == 3
assert len(strand("review", "status")["reviews"]) == 3
assert len(strand("agent", "runs")) == 6
if not args.keep_open:
    repl('''(review/close! {:runtime (c/runtime)})''')
print("PASS: actual JSONL, MR filter, last-100 bound, linked durable logs, scoped burn, daily cleanup with polling disabled", flush=True)
assert before == [git("branch", "--show-current"), git("diff", "--binary", "HEAD"), git("status", "--porcelain")]
print("PASS: scheduled new-revision review, stale prior revision, cancellation, and canonical work preserved", flush=True)
print(json.dumps({"cards": [c["id"] for c in strand("review", "status")["reviews"]],
                  "publishedReview": card["id"], "runs": 6, "workspace": str(workspace),
                  "mockGitLab": str(fake_glab), "keptOpen": args.keep_open}, sort_keys=True), flush=True)
