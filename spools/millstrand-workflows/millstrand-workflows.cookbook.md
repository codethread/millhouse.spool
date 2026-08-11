# Millstrand-workflows cookbook

## Publish one macro-owning root

Start `publish-spool-kondo` with the owning root's public identity and an
explicit vector of macro-to-hook mappings:

```clojure
{:spool-root "spools/example-macros"
 :namespace "example.macros"
 :spool-key "example/macros"
 :macro-forms [{:macro "example.macros/defwidget"
                :hook "hooks.example/defwidget"}
               {:macro "example.macros/defpanel"
                :hook "hooks.example/defpanel"}]}
```

The root should have this shape:

```text
spools/example-macros/
├── deps.edn                         # :paths includes src and resources
├── resources/
│   └── clj-kondo.exports/
│       └── example.macros/
│           └── config.edn
└── src/
    └── example/macros.clj
```

The export config names the macro shapes and hook namespaces. Keep hook source
under the exported resource tree when it is library-specific, and test the
resource through `io/resource` from a clean consumer classpath. A consumer's
project `.clj-kondo/config.edn` is for local overrides; it is not a substitute
for publishing the root export.

## Shape changes

When a macro's binding or body shape changes, update the matching hook and export
mapping together. Run the focused root test, clj-kondo against a consumer that
sees the root as a dependency, and the repository quality checks. Then update the
contract and generated API documentation so the mapping remains discoverable.

Do not add a repository scan that guesses macro names. The mapping is intentionally
authored because it is the compatibility contract between a macro owner and its
consumers.
