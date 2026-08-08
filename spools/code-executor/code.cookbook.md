# Code executor cookbook

The code executor runs an approved Clojure function for a ready workflow gate.

## Run a code gate

Define a public function in a module that loads before the code executor, then name that function with the gate's `code/fn` attribute.

```clojure
(defn summarise [{:keys [text]}]
  {:summary text})
```

Use `:workflow/gate "code"`, `:code/fn "my.code-functions/summarise"`, and a JSON-object `:code/params` value in the gate attributes. The executor resolves the Var when the gate runs and passes the poured parameters map directly to it. The returned value must satisfy the outcome rules in the [contract](./README.md).

## Choose the right executor

Use the code executor when trusted in-process Clojure is the intended authority. Use the [shell executor](../shell-executor/README.md) when the work must run as a separate operating-system process.

## See also

- [Contract](./README.md)
- [API reference](./code.api.md)
- [Workflow contract](../workflow/README.md)
