# inlay

Fetch and verify WebAssembly modules from OCI registries for the JVM.

## Quick start

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.roastedroot</groupId>
      <artifactId>inlay-maven-plugin</artifactId>
      <version>${inlay.version}</version>
      <executions>
        <execution>
          <goals><goal>fetch</goal></goals>
          <configuration>
            <modules>
              <module>
                <imageRef>ghcr.io/roastedroot/sqlite4j-wasm:3.51.0</imageRef>
                <outputFile>${project.build.directory}/wasm/libsqlite3.wasm</outputFile>
              </module>
            </modules>
          </configuration>
        </execution>
      </executions>
    </plugin>

    <plugin>
      <groupId>run.endive</groupId>
      <artifactId>endive-compiler-maven-plugin</artifactId>
      <version>${endive.version}</version>
      <executions>
        <execution>
          <goals><goal>compile</goal></goals>
          <configuration>
            <wasmFile>${project.build.directory}/wasm/libsqlite3.wasm</wasmFile>
            <name>com.example.SqliteModule</name>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

See [examples/basic/](examples/basic/).

## Caching

Fetched artifacts are cached in `~/.cache/inlay/` (or `$XDG_CACHE_HOME/inlay/`), keyed by OCI digest. Survives `mvn clean`.

| Flag | Behavior |
|------|----------|
| (default) | Cache hit → no network |
| `-Dinlay.update` | Re-resolves digest, updates lock file |
| `-Dinlay.noCache` | Always pulls from registry |

## Lock file

`inlay:fetch` writes `wkg.lock` using the [wkg format](https://github.com/bytecodealliance/wasm-pkg-tools) (Rust [wasm-pkg-common](https://crates.io/crates/wasm-pkg-common) types compiled to wasm). Commit it for reproducible builds.

If the registry digest changes but the lock hasn't been updated, the build fails.

### Upgrading

```sh
mvn io.roastedroot:inlay-maven-plugin:fetch -Dinlay.update
```

## Authentication

Resolved in order:

1. Maven `settings.xml` `<server>` entries keyed by registry hostname
2. Docker/Podman credential stores (`~/.docker/config.json`)

For local dev: [`oras login ghcr.io`](https://oras.land/docs/installation) or `docker login ghcr.io`. For CI:

```xml
<server>
  <id>ghcr.io</id>
  <username>${env.GHCR_USER}</username>
  <password>${env.GHCR_TOKEN}</password>
</server>
```

## Package references

Use wkg-style names instead of full OCI refs. Namespaces resolve via `~/.config/wasm-pkg/config.toml`:

```xml
<module>
  <packageRef>roastedroot:sqlite4j-wasm@3.51.0</packageRef>
</module>
```

Builtins: `wasi:*` → `wasi.dev`, `ba:*` → `bytecodealliance.org`.

## Signature verification

Verification runs inline after fetch — no separate step. Configure on each module:

```xml
<module>
  <imageRef>ghcr.io/roastedroot/sqlite4j-wasm:3.51.0</imageRef>
  <outputFile>${project.build.directory}/wasm/libsqlite3.wasm</outputFile>
  <sigstoreIssuer>https://token.actions.githubusercontent.com</sigstoreIssuer>
  <sigstoreIdentity>https://github.com/roastedroot/*</sigstoreIdentity>
</module>
```

Uses [sigstore-java](https://github.com/sigstore/sigstore-java) keyless verification. Expects a `.sigstore.json` bundle alongside the artifact.

## Publishing wasm to OCI

Requires [oras CLI](https://oras.land/docs/installation) and optionally [cosign](https://docs.sigstore.dev/cosign/system_config/installation/). Packages appear at `https://github.com/orgs/<org>/packages` — [set visibility to public](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility) after first push.

```sh
echo $GHCR_TOKEN | oras login ghcr.io -u $GHCR_USER --password-stdin

oras push ghcr.io/roastedroot/sqlite4j-wasm:3.51.0 \
  libsqlite3.wasm:application/wasm

cosign sign --yes ghcr.io/roastedroot/sqlite4j-wasm:3.51.0
```

### GitHub Actions — publisher

```yaml
name: Publish Wasm
on:
  push:
    paths: ['wasm-build/**']
    branches: [main]
permissions:
  contents: read
  packages: write
  id-token: write
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: oras-project/setup-oras@v1
      - uses: sigstore/cosign-installer@v3
      - run: ./wasm-build/build.sh
      - run: echo "${{ secrets.GITHUB_TOKEN }}" | oras login ghcr.io -u ${{ github.actor }} --password-stdin
      - run: |
          oras push ghcr.io/roastedroot/my-module-wasm:1.0.0 \
            wasm-build/output/my-module.wasm:application/wasm
          cosign sign --yes ghcr.io/roastedroot/my-module-wasm:1.0.0
```

### GitHub Actions — consumer

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: 'maven'
      - run: mvn verify
```

No wasm build step, no Docker, no Rust. `inlay:fetch` pulls from GHCR during `generate-sources`.

## Building from source

Requires JDK 17+ and Rust with `wasm32-wasip1` target. Docker for integration tests.

```sh
cd wkg-wasm && make build
mvn install                  # unit tests
mvn verify                   # unit + integration tests (Docker required)
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for architecture and development details.
