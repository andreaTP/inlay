# Inlay: Status

## Architecture

```
inlay/
├── core/           InlayClient, LockFile, LockedPackage, InlayException
│                   WkgParser (package-private, memory-direct wasm boundary)
├── maven-plugin/   FetchMojo (fetch + inline sigstore verify), ModuleConfig
├── wkg-wasm/       Rust crate → wasm-pkg-common types → wasm32-wasip1
├── examples/basic/ Standalone example pom
└── .github/        CI + reusable wasm-publish workflow
```

## Tests

- 25 unit tests (core) + 2 unit tests (maven-plugin) + 6 integration tests (testcontainers Zot)
- Input validation, lock file round-trip, wkg format compatibility, malformed TOML error reporting
- Full pipeline: push to Zot → fetch → run on Endive (iterFact)
- FetchMojo: invalid imageRef detection, missing config detection

## Remaining

### Phase 6: Rollout to roastedroot repos
1. **sqlite4j** — pilot
2. **jq4j** → **quickjs4j** → **sqlite4j2** → **prism** → **pglite4j** → **lumis4j**
