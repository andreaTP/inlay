# Contributing to inlay

## Prerequisites

- JDK 17+
- Rust with `wasm32-wasip1` target (`rustup target add wasm32-wasip1`)
- Docker (for testcontainers integration tests)

## Building

```sh
cd wkg-wasm && make build   # compile Rust → wasm32-wasip1
mvn install                  # build + unit tests
```

## Running tests

```sh
mvn test                     # unit tests only (no Docker needed)
mvn verify                   # unit + integration tests (needs Docker)
```

Integration tests use [testcontainers](https://testcontainers.com/) with a [Zot](https://github.com/project-zot/zot) OCI registry. Docker must be running.

## Architecture

```
inlay/
├── core/           Java library — InlayClient, LockFile, LockedPackage
│                   WkgParser (package-private, delegates TOML to Rust wasm)
├── maven-plugin/   FetchMojo — the user-facing Maven plugin
├── wkg-wasm/       Rust crate compiled to wasm32-wasip1
│                   Uses real wasm-pkg-common types for wkg format compatibility
└── examples/       Standalone Maven project templates
```

### Design principles

- **Connect dots, don't reinvent.** ORAS SDK handles OCI pull/push. Endive runs wasm. Rust `toml` + `wasm-pkg-common` handle the wkg lock file format. sigstore-java handles verification. inlay just wires them together.
- **WkgParser is internal.** Users interact with `LockFile.read(path)` / `lock.write(path)` and `InlayClient.builder()`. The wasm boundary is hidden.
- **Valid semver only** for lock file versions. No custom version normalization.
- **Memory-direct wasm boundary.** No JSON round-trip — length-prefixed strings in linear memory. Drops `serde_json` from Rust.

### Lock file format

Uses the [wkg.lock format](https://github.com/bytecodealliance/wasm-pkg-tools) with `[[packages]]` / `[[packages.versions]]` table syntax. TOML serialization uses the real Rust `wasm-pkg-common` types (`PackageRef`, `ContentDigest`, `Version`, `VersionReq`) for exact format compatibility with the `wkg` CLI.

### Rust crate (`wkg-wasm/`)

Exports: `resolve_namespace`, `read_lock`, `write_lock`, `alloc`, `dealloc`. Compiled with `opt-level = "z"`, `lto = true`, `panic = "abort"`. AOT compiled by `endive-compiler-maven-plugin` at Java build time — no raw `.wasm` in the jar.
