
-----
# <a name="millhouse.spools.identity">millhouse.spools.identity</a>


Logical harness-session identities and run provenance.




## <a name="millhouse.spools.identity/bind!">`bind!`</a>
``` clojure
(bind! runtime {:keys [harness native-session-id model thinking-level run-id expected-identity], :as request})
```
Function.

Mint or recover the identity for one native logical harness session.

  Replays are idempotent. `:expected-identity` makes resume mismatch loud. When
  `:run-id` is supplied, the identity records a `performed` edge to that strand.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L72-L109">Source</a></sub></p>

## <a name="millhouse.spools.identity/current">`current`</a>
``` clojure
(current runtime friendly-id)
```
Function.

Resolve an existing identity by friendly ID, failing when absent or ambiguous.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L111-L118">Source</a></sub></p>

## <a name="millhouse.spools.identity/identity">`identity`</a>
``` clojure
(identity #:op{:keys [runtime args]})
```
Function.

Dispatch `strand identity` operations.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L137-L145">Source</a></sub></p>

## <a name="millhouse.spools.identity/identity?">`identity?`</a>
``` clojure
(identity? strand)
```
Function.

Return true when `strand` is an identity record.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L37-L40">Source</a></sub></p>
