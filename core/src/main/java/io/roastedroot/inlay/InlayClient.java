package io.roastedroot.inlay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import land.oras.ArtifactType;
import land.oras.ContainerRef;
import land.oras.Descriptor;
import land.oras.Layer;
import land.oras.Manifest;
import land.oras.ManifestDescriptor;
import land.oras.Referrers;
import land.oras.Registry;
import land.oras.policy.ContainersPolicy;
import land.oras.utils.Const;

public final class InlayClient {

    private final Registry registry;
    private final Path cacheDir;

    private InlayClient(Registry registry, Path cacheDir) {
        this.registry = registry;
        this.cacheDir = cacheDir;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Path pull(String imageRef, Path outputFile) {
        if (imageRef == null || imageRef.isEmpty()) {
            throw new InlayException("imageRef must not be null or empty");
        }
        if (outputFile == null) {
            throw new InlayException("outputFile must not be null");
        }
        String digest = getDigest(imageRef);
        return pullByDigest(imageRef, digest, outputFile);
    }

    public Path pullByDigest(String imageRef, String digest, Path outputFile) {
        if (imageRef == null || imageRef.isEmpty()) {
            throw new InlayException("imageRef must not be null or empty");
        }
        if (digest == null || digest.isEmpty()) {
            throw new InlayException("digest must not be null or empty");
        }
        if (outputFile == null) {
            throw new InlayException("outputFile must not be null");
        }

        if (cacheDir != null) {
            Path cached = cachePath(digest);
            if (Files.exists(cached)) {
                return copyToOutput(cached, outputFile);
            }
        }

        ContainerRef containerRef = ContainerRef.parse(imageRef);

        try {
            Path tempDir = Files.createTempDirectory("inlay-pull-");
            try {
                registry.pullArtifact(containerRef, tempDir, true);

                Path pulled = findWasmFile(tempDir);
                if (pulled == null) {
                    throw new InlayException("No .wasm file found in pulled artifact: " + imageRef);
                }

                if (cacheDir != null) {
                    Path cached = cachePath(digest);
                    Files.createDirectories(cached.getParent());
                    Files.copy(pulled, cached, StandardCopyOption.REPLACE_EXISTING);
                    return copyToOutput(cached, outputFile);
                }

                return copyToOutput(pulled, outputFile);
            } finally {
                deleteRecursive(tempDir);
            }
        } catch (InlayException e) {
            throw e;
        } catch (IOException e) {
            throw new InlayException("Failed to pull artifact: " + imageRef, e);
        }
    }

    public Path fetchSigstoreBundle(String imageRef, String digest, Path bundlePath) {
        if (imageRef == null || imageRef.isEmpty()) {
            throw new InlayException("imageRef must not be null or empty");
        }
        try {
            ContainerRef containerRef = ContainerRef.parse(imageRef);
            ContainerRef digestRef = containerRef.withDigest(digest);
            Referrers referrers =
                    registry.getReferrers(
                            digestRef, ArtifactType.from(Const.SIGSTORE_BUNDLE_MEDIA_TYPE));
            for (ManifestDescriptor referrer : referrers.getManifests()) {
                if (!Const.SIGSTORE_BUNDLE_MEDIA_TYPE.equals(referrer.getArtifactType())) {
                    continue;
                }
                Descriptor descriptor =
                        registry.getDescriptor(digestRef.withDigest(referrer.getDigest()));
                Manifest signatureManifest = Manifest.fromJson(descriptor.getJson());
                for (Layer layer : signatureManifest.getLayers()) {
                    if (Const.SIGSTORE_BUNDLE_MEDIA_TYPE.equals(layer.getMediaType())) {
                        byte[] bundle = registry.getBlob(digestRef.withDigest(layer.getDigest()));
                        Files.createDirectories(bundlePath.getParent());
                        Files.write(bundlePath, bundle);
                        return bundlePath;
                    }
                }
            }
        } catch (InlayException e) {
            throw e;
        } catch (RuntimeException | IOException e) {
            throw new InlayException("Failed to fetch sigstore bundle for: " + imageRef, e);
        }
        return null;
    }

    public String getDigest(String imageRef) {
        if (imageRef == null || imageRef.isEmpty()) {
            throw new InlayException("imageRef must not be null or empty");
        }
        try {
            ContainerRef containerRef = ContainerRef.parse(imageRef);
            return registry.getManifest(containerRef).getDescriptor().getDigest();
        } catch (InlayException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InlayException("Failed to resolve digest for: " + imageRef, e);
        }
    }

    private static Path copyToOutput(Path source, Path outputFile) {
        try {
            Files.createDirectories(outputFile.getParent());
            Files.copy(source, outputFile, StandardCopyOption.REPLACE_EXISTING);
            return outputFile;
        } catch (IOException e) {
            throw new InlayException("Failed to copy to output: " + outputFile, e);
        }
    }

    private Path cachePath(String digest) {
        String safe = digest.replace(":", "/");
        return cacheDir.resolve(safe).resolve("artifact.wasm");
    }

    private static Path findWasmFile(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".wasm"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void deleteRecursive(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException e) {
                                    // best-effort cleanup
                                }
                            });
        } catch (IOException e) {
            // best-effort cleanup
        }
    }

    private static Path defaultCacheDir() {
        String xdgCache = System.getenv("XDG_CACHE_HOME");
        if (xdgCache != null && !xdgCache.isEmpty()) {
            return Path.of(xdgCache, "inlay");
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            return Path.of(home, ".cache", "inlay");
        }
        return null;
    }

    public static final class Builder {
        private String username;
        private String password;
        private boolean insecure;
        private boolean noCache;
        private Path cacheDir;

        private Builder() {}

        public Builder withCredentials(String username, String password) {
            this.username = username;
            this.password = password;
            return this;
        }

        public Builder insecure() {
            this.insecure = true;
            return this;
        }

        public Builder withCacheDir(Path cacheDir) {
            this.cacheDir = cacheDir;
            return this;
        }

        public Builder noCache() {
            this.noCache = true;
            return this;
        }

        public InlayClient build() {
            Registry.Builder rb = Registry.builder();
            if (insecure) {
                rb.insecure();
            } else if (username != null) {
                rb.defaults(username, password);
            } else {
                rb.defaults();
            }
            rb.withPolicy(ContainersPolicy.newPolicy());

            Path cache = noCache ? null : (cacheDir != null ? cacheDir : defaultCacheDir());
            return new InlayClient(rb.build(), cache);
        }
    }
}
