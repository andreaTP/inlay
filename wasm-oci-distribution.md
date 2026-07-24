# Distributing Wasm on the JVM via OCI Artifacts

## Project Name

**inlay** — from "inlay grafting," a technique where material is inserted into rootstock. The tool inserts external Wasm modules (from OCI registries) into the JVM rootstock. Coordinates: `io.roastedroot:inlay`. Maven plugin: `io.roastedroot:inlay-maven-plugin`.

## Context

Endive is a JVM-native WebAssembly runtime. The [Endive Host demo](https://wasmcloud.com/community/2026-05-27-community-meeting/) at the wasmCloud community meeting showed pulling Wasm modules from OCI registries and running them as wasmCloud workloads in a JVM - demonstrating the viability of this approach.

Today, end users must manually obtain `.wasm` files. This project would formalize the OCI distribution story with proper build-tool integration, dependency resolution via `wkg` standard formats, and supply-chain security.

The Wasm ecosystem has converged on **OCI registries** + the **Bytecode Alliance `wkg` tooling** as the standard distribution channel:

| Project | OCI Wasm Usage |
|---------|-------|
| [wasmCloud](https://wasmcloud.com/docs/v1/concepts/packaging/) | Components & providers as OCI |
| [Spin/Fermyon](https://www.fermyon.com/) (Akamai) | OCI for Spin apps, 75M req/sec edge |
| [containerd/runwasi](https://github.com/containerd/runwasi) | Wasm OCI artifacts as K8s pods |
| [SpinKube](https://github.com/cncf/sandbox/issues/90) (CNCF Sandbox) | K8s-native Wasm scheduling |
| [Istio/Envoy](https://istio.io/latest/docs/tasks/extensibility/wasm-module-distribution/) | OCI is the **recommended production** path for Wasm plugins |
| [cargo-component](https://github.com/bytecodealliance/cargo-component), [componentize-dotnet](https://github.com/bytecodealliance/componentize-dotnet) | Language toolchains publish to OCI |

**This should be a standalone project** so any JVM Wasm runtime can benefit.

---

## The Standards

### CNCF Wasm OCI Artifact Format

Defined by the [CNCF TAG Runtime](https://tag-runtime.cncf.io/wgs/wasm/deliverables/wasm-oci-artifact/).

| Purpose | Media Type |
|---------|-----------|
| Manifest | `application/vnd.oci.image.manifest.v1+json` |
| Config | `application/vnd.wasm.config.v0+json` |
| Layers | `application/wasm` |

The config carries component metadata (imports, exports, target world). The layer is the raw `.wasm` binary. Works with any OCI 1.1 registry (GHCR, Docker Hub, ACR, ECR, etc.). Currently single-layer. Architecture is always `wasm`, OS is `wasip1` or `wasip2`.

### Bytecode Alliance `wkg` Tooling

[`wkg`](https://github.com/bytecodealliance/wasm-pkg-tools) is the standard cross-language package management tool for Wasm. It defines:

- **`config.toml`** - namespace-to-registry mapping (e.g., `wasi:*` → `ghcr.io/webassembly/...`)
- **`wkg.lock`** - pinned versions with OCI digests for reproducibility

Both formats are [explicitly designed to be cross-language](https://component-model.bytecodealliance.org/composing-and-distributing/distributing.html): "This config file is meant to be used by both wkg and also any other language-specific component tooling."

---

## Reusing wasm-pkg-tools Without Reimplementation

### Crate Architecture

```
wasm-pkg-common (types + optional config loading)
├── Registry, PackageRef, ContentDigest, Version (pure serde types)
├── config/ (TOML parsing + file loading, feature-gated)
└── digest, label, metadata, package (pure computation)

wasm-pkg-client (network I/O layer)
├── OCI registry client (reqwest, oci-client, oci-wasm)
├── Warg protocol support
├── Docker credential helpers
└── Re-exports types from wasm-pkg-common

wasm-pkg-core (orchestration)
├── lock.rs - Lock file types (pure serde) + file locking (tokio, flock)
├── resolver.rs - Resolution algorithm (calls client for network I/O)
├── manifest.rs - wkg.toml handling
└── wit.rs - WIT dependency operations
```

### What CAN Compile to Wasm

**`wasm-pkg-common` WITHOUT features** has these dependencies:
`anyhow`, `bytes`, `futures-util`, `http` (types only), `semver`, `serde`, `serde_json`, `sha2`, `thiserror`, `tracing`

**All of these are pure computation and compile to `wasm32-wasip1`.** The core types (`Registry`, `PackageRef`, `ContentDigest`) are clean serde-derived structs with no I/O.

Adding `toml` (also pure computation) gives config and lock file PARSING.

### The Lock File Types

The lock file serde types in `wasm-pkg-core/lock.rs` are trivial and depend only on `wasm-pkg-common` types:

```rust
struct LockedPackage { name: PackageRef, registry: Option<String>, versions: Vec<LockedPackageVersion> }
struct LockedPackageVersion { requirement: VersionReq, version: Version, digest: ContentDigest }
```

The I/O (file locking via `flock`/`LockFileEx`, tokio filesystem) is in the METHODS, not the types.

### Approach: Thin Rust Wrapper → Wasm → Endive

Create a small Rust crate that:

1. **Depends on `wasm-pkg-common`** (without `registry-config` feature) for the base types
2. **Adds `toml`** for parsing (pure computation, compiles to Wasm)
3. **Mirrors the 3 lock file serde structs** from `wasm-pkg-core` (they depend only on `wasm-pkg-common` types)
4. **Exports WASI functions:**
   - `parse_config(toml_string) → json` (config.toml → structured data)
   - `parse_lock(lock_string) → json` (wkg.lock → structured data)
   - `resolve_namespace(config, namespace) → registry_url`
5. **Compiles to `wasm32-wasip1`**
6. **Runs on Endive** - Java reads files from disk, passes strings to Wasm, gets parsed structures back

**What this gives us:**
- **No reimplementation** of format parsing - uses the actual Rust types
- **Exact compatibility** with `wkg` - same serde types guarantee format fidelity
- **Bootstrapping story** - Endive uses Wasm to manage Wasm packages
- **Automatic format updates** - bump the `wasm-pkg-common` dependency to track upstream
- Java side handles all I/O (file reads via Java, OCI pulls via ORAS Java SDK)

**Potential upstream contribution:** Propose a `wasm-pkg-types` crate or feature flag to the Bytecode Alliance that cleanly separates pure types from I/O, making Wasm compilation a first-class use case.

---

## Maven Plugin Design

Inspired by [oci-artifact-maven-plugin](https://github.com/adambkaplan/oci-artifact-maven-plugin).

### Plugin Goals

| Goal | Phase | Description |
|------|-------|-------------|
| `fetch` | `generate-sources` | Pull `.wasm` from OCI registry into `target/wasm/` |
| `verify` | `validate` | Verify cosign/sigstore signatures before fetching |

### Fetch Configuration

```xml
<plugin>
  <groupId>TBD</groupId>
  <artifactId>TBD-maven-plugin</artifactId>
  <executions>
    <execution>
      <goals><goal>fetch</goal></goals>
      <configuration>
        <!-- Option 1: Direct OCI reference -->
        <modules>
          <module>
            <imageRef>ghcr.io/example/my-component:1.0.0</imageRef>
            <!-- or pinned: ghcr.io/example/my-component@sha256:abc123... -->
            <outputFile>${project.build.directory}/wasm/my-component.wasm</outputFile>
          </module>
        </modules>
        <!-- Option 2: wkg package name (resolved via config.toml) -->
        <packages>
          <package>wasi:http@0.2.3</package>
        </packages>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Auth via Maven `settings.xml`:
```xml
<server>
  <id>ghcr.io</id>
  <username>${env.GHCR_USER}</username>
  <password>${env.GHCR_TOKEN}</password>
</server>
```

### Chaining with Endive Build-Time Compiler

```xml
<!-- 1. Fetch from OCI -->
<plugin>
  <groupId>TBD</groupId>
  <artifactId>TBD-maven-plugin</artifactId>
  <executions>
    <execution>
      <goals><goal>fetch</goal></goals>
      <configuration>
        <modules>
          <module>
            <imageRef>ghcr.io/example/my-component:1.0.0</imageRef>
            <outputFile>${project.build.directory}/wasm/my-component.wasm</outputFile>
          </module>
        </modules>
      </configuration>
    </execution>
  </executions>
</plugin>

<!-- 2. Compile to JVM bytecode -->
<plugin>
  <groupId>run.endive</groupId>
  <artifactId>endive-compiler-maven-plugin</artifactId>
  <configuration>
    <wasmFile>${project.build.directory}/wasm/my-component.wasm</wasmFile>
  </configuration>
</plugin>
```

---

## Securing the Lifecycle

### Building Blocks

| Library | Purpose | Coordinates |
|---------|---------|-------------|
| [ORAS Java SDK](https://github.com/oras-project/oras-java) | OCI push/pull, auth, referrers, trust policy | `land.oras:oras-java-sdk:0.8.0` |
| [sigstore-java](https://github.com/sigstore/sigstore-java) | Keyless signing & verification | `dev.sigstore:sigstore-java` |

Both Apache 2.0, on Maven Central. ORAS has a [Quarkus extension](https://docs.quarkiverse.io/quarkus-oras/dev/index.html). sigstore-java has both a [Maven plugin](https://github.com/sigstore/sigstore-maven-plugin) and [Gradle plugin](https://github.com/sigstore/sigstore-java/blob/main/sigstore-gradle/README.md).

### Secured Flow

```
Author → CI/CD (compile → push to OCI → cosign sign) → Consumer (verify → fetch → compile to JVM) → Runtime
```

### Verify Goal

```xml
<execution>
  <goals><goal>verify</goal></goals>
  <configuration>
    <modules>
      <module>
        <imageRef>ghcr.io/example/my-component:1.0.0</imageRef>
        <!-- Option 1: cosign public key -->
        <cosignKey>cosign.pub</cosignKey>
        <!-- Option 2: keyless with identity constraints -->
        <sigstore>
          <issuer>https://token.actions.githubusercontent.com</issuer>
          <identity>https://github.com/example/...release.yml@refs/tags/v1.0.0</identity>
        </sigstore>
      </module>
    </modules>
  </configuration>
</execution>
```

### What the Libraries Provide

**ORAS Java SDK:** Push/pull with custom media types, Docker credential store auth, trust policy (Podman/Skopeo-compatible), sigstore keyed verification via OCI referrers, `registries.conf` support, OCI Layout for offline dev.

**sigstore-java:** Keyless signing (OIDC), `KeylessVerifier` API, offline verification via bundle JSON.

---

## Easy & Free OCI Registries

| Registry | Auth | Setup | Persistence | Best For |
|----------|------|-------|-------------|----------|
| [**ttl.sh**](https://ttl.sh) | **None** | Zero | Ephemeral (auto-deletes) | Quick testing, demos, CI |
| [**Zot**](https://github.com/project-zot/zot) | None default | `docker run -p 5000:5000 ghcr.io/project-zot/zot:latest` | Persistent | Local dev, self-hosted |
| [**CNCF Distribution**](https://github.com/distribution/distribution) | None default | `docker run -p 5000:5000 registry:2` | In-container | Integration tests |
| [**GHCR**](https://ghcr.io) | PAT token | Needs token setup | Persistent | Production, open source |

**For zero-friction testing:** `ttl.sh` - just `oras push ttl.sh/my-test:1h my-component.wasm` with no auth, no setup, no account.

**For local development:** `docker run -p 5000:5000 ghcr.io/project-zot/zot:latest` gives you a full OCI registry with ORAS/cosign support.

---

## Implementation Dependencies

| Dependency | Why |
|-----------|-----|
| `land.oras:oras-java-sdk` | OCI pull, auth, referrers, trust policy |
| `dev.sigstore:sigstore-java` | Keyless signing & verification |
| `org.apache.maven:maven-plugin-api` | Maven plugin development |
| Compiled `wasm-pkg-common` wrapper (`.wasm`) | Config/lock file parsing (no reimplementation) |

---

## Next Steps

1. **Spike 1:** Compile `wasm-pkg-common` (no features) + `toml` to `wasm32-wasip1` and verify it works on Endive
2. **Spike 2:** Use ORAS Java SDK to pull a wasmCloud component from GHCR and load it with `Parser.parse()`
3. **Scaffold** Maven plugin with `fetch` and `verify` goals
4. **Test** against ttl.sh (zero-setup) and local Zot registry
5. **Integrate** signature verification via sigstore-java
6. **Upstream:** Propose `wasm-pkg-types` extraction to Bytecode Alliance

---

## Sources

- [CNCF Wasm OCI Artifact Layout](https://tag-runtime.cncf.io/wgs/wasm/deliverables/wasm-oci-artifact/)
- [wasmCloud Packaging](https://wasmcloud.com/docs/v1/concepts/packaging/)
- [Endive Host demo at wasmCloud community meeting](https://wasmcloud.com/community/2026-05-27-community-meeting/)
- [ORAS Java SDK](https://github.com/oras-project/oras-java)
- [sigstore-java](https://github.com/sigstore/sigstore-java)
- [Bytecode Alliance wasm-pkg-tools](https://github.com/bytecodealliance/wasm-pkg-tools)
- [Component Model: Distributing and Fetching](https://component-model.bytecodealliance.org/composing-and-distributing/distributing.html)
- [oci-artifact-maven-plugin](https://github.com/adambkaplan/oci-artifact-maven-plugin)
- [wasmCloud Cosign signing](https://wasmcloud.com/blog/2025-09-02-securely-signing-wasm-components-with-cosign-oidc/)
- [Zot OCI Registry](https://github.com/project-zot/zot)
- [ttl.sh](https://ttl.sh)
- [ORAS Compatible Registries](https://oras.land/docs/compatible_oci_registries/)
- [WebAssembly in 2026 for Java/Kotlin](https://www.javacodegeeks.com/2026/04/webassembly-in-2026-where-it-has-landed-what-wasi-0-2-changes-and-why-java-and-kotlin-developers-should-pay-attention-now.html)
- [Microsoft: Distributing Wasm components using OCI](https://opensource.microsoft.com/blog/2024/09/25/distributing-webassembly-components-using-oci-registries/)
- [Istio Wasm module distribution](https://istio.io/latest/docs/tasks/extensibility/wasm-module-distribution/)
- [Supply-Chain Security for Java](https://medium.com/@27.rahul.k/supply-chain-security-for-java-0651c1e21976)
- [Renovate custom regex manager](https://docs.renovatebot.com/modules/manager/regex/)
- [Seqera Maven OCI Registry](https://github.com/seqeralabs/maven-oci-registry) (evaluated, not adopted)
