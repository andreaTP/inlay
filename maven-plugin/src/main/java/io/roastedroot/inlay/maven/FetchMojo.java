package io.roastedroot.inlay.maven;

import dev.sigstore.KeylessVerificationException;
import dev.sigstore.KeylessVerifier;
import dev.sigstore.VerificationOptions;
import dev.sigstore.VerificationOptions.CertificateMatcher;
import dev.sigstore.bundle.Bundle;
import dev.sigstore.bundle.BundleParseException;
import dev.sigstore.strings.StringMatcher;
import dev.sigstore.trustroot.SigstoreConfigurationException;
import io.roastedroot.inlay.InlayClient;
import io.roastedroot.inlay.InlayException;
import io.roastedroot.inlay.LockFile;
import io.roastedroot.inlay.LockedPackage;
import io.roastedroot.inlay.WkgParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;

@Mojo(name = "fetch", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class FetchMojo extends AbstractMojo {

    @Parameter(required = true)
    private List<ModuleConfig> modules;

    @Parameter(
            required = true,
            defaultValue = "${project.basedir}/wkg.lock",
            property = "inlay.lockFile")
    private File lockFile;

    @Parameter(defaultValue = "false", property = "inlay.update")
    private boolean update;

    @Parameter(defaultValue = "false", property = "inlay.noCache")
    private boolean noCache;

    @Parameter(defaultValue = "false", property = "inlay.insecure")
    private boolean insecure;

    @Parameter(property = "inlay.configFile")
    private File configFile;

    @Parameter(defaultValue = "false", property = "inlay.skip")
    private boolean skip;

    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    @Parameter(property = "settings", required = true, readonly = true)
    private Settings settings;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping inlay:fetch (inlay.skip=true)");
            return;
        }
        try (WkgParser parser = new WkgParser()) {
            LockFile lock;
            try {
                lock = LockFile.read(parser, lockFile.toPath());
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to read lock file: " + lockFile, e);
            }

            boolean lockChanged = false;

            for (ModuleConfig module : modules) {
                boolean changed = fetchModule(module, lock, parser);
                lockChanged = lockChanged || changed;
            }

            if (lockChanged) {
                try {
                    lock.write(lockFile.toPath());
                    getLog().info("Updated lock file: " + lockFile);
                } catch (IOException e) {
                    throw new MojoExecutionException("Failed to write lock file: " + lockFile, e);
                }
            }
        }
    }

    private boolean fetchModule(ModuleConfig module, LockFile lock, WkgParser parser)
            throws MojoExecutionException {
        String imageRef = module.getImageRef();

        if ((imageRef == null || imageRef.isEmpty()) && module.getPackageRef() != null) {
            imageRef = resolvePackageRef(module.getPackageRef(), lock);
        }

        if (imageRef == null || imageRef.isEmpty()) {
            throw new MojoExecutionException(
                    "Either imageRef or packageRef must be specified for each module");
        }

        if (!imageRef.contains("/")) {
            throw new MojoExecutionException(
                    "Invalid imageRef (expected registry/repository[:tag]): " + imageRef);
        }

        File outputFile = module.getOutputFile();
        if (outputFile == null) {
            outputFile = defaultOutputFile(imageRef);
        }

        Path outputPath = outputFile.toPath();

        LockedPackage locked = lock.findByImageRef(imageRef);

        if (locked != null && !update && Files.exists(outputPath)) {
            getLog().info("Already fetched (locked): " + imageRef);
            return false;
        }

        getLog().info("Fetching " + imageRef + " -> " + outputPath);

        try {
            InlayClient client = createClient(imageRef);

            String resolvedDigest = client.getDigest(imageRef);

            if (locked != null && !update && !locked.getDigest().equals(resolvedDigest)) {
                throw new MojoExecutionException(
                        "Digest mismatch for "
                                + imageRef
                                + ": lock file has "
                                + locked.getDigest()
                                + " but registry resolved "
                                + resolvedDigest
                                + ". Run with -Dinlay.update to accept the new digest.");
            }

            boolean needsVerification =
                    module.getSigstoreIssuer() != null || module.getSigstoreIdentity() != null;

            if (needsVerification) {
                Path tempPath = null;
                try {
                    Files.createDirectories(outputPath.getParent());
                    tempPath = Files.createTempFile(outputPath.getParent(), "inlay-", ".wasm.tmp");
                    client.pullByDigest(imageRef, resolvedDigest, tempPath);
                    verifySigstore(client, imageRef, resolvedDigest, tempPath, module);
                    Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
                    tempPath = null;
                } finally {
                    if (tempPath != null) {
                        Files.deleteIfExists(tempPath);
                    }
                }
            } else {
                client.pullByDigest(imageRef, resolvedDigest, outputPath);
            }

            getLog().info("Fetched " + imageRef + " -> " + outputPath);

            lock.addOrUpdate(imageRef, resolvedDigest);
            return true;
        } catch (InlayException e) {
            throw new MojoExecutionException(
                    "Failed to fetch " + imageRef + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to fetch " + imageRef + ": " + e.getMessage(), e);
        }
    }

    private void verifySigstore(
            InlayClient client,
            String imageRef,
            String digest,
            Path artifactPath,
            ModuleConfig module)
            throws MojoExecutionException {
        Path bundlePath = Path.of(artifactPath + ".sigstore.json");

        if (!Files.exists(bundlePath)) {
            getLog().info("Fetching sigstore bundle for " + imageRef);
            Path fetched = client.fetchSigstoreBundle(imageRef, digest, bundlePath);
            if (fetched == null) {
                throw new MojoExecutionException(
                        "No sigstore bundle found for "
                                + imageRef
                                + ". Sign the artifact with: cosign sign --yes "
                                + imageRef);
            }
        }

        getLog().info("Verifying signature for " + artifactPath);

        try {
            Bundle bundle = Bundle.from(bundlePath, StandardCharsets.UTF_8);

            var optionsBuilder = VerificationOptions.builder();
            var matcherBuilder = CertificateMatcher.fulcio();
            if (module.getSigstoreIdentity() != null) {
                matcherBuilder.subjectAlternativeName(
                        StringMatcher.string(module.getSigstoreIdentity()));
            }
            if (module.getSigstoreIssuer() != null) {
                matcherBuilder.issuer(StringMatcher.string(module.getSigstoreIssuer()));
            }
            optionsBuilder.addCertificateMatchers(matcherBuilder.build());

            KeylessVerifier verifier = KeylessVerifier.builder().sigstorePublicDefaults().build();
            verifier.verify(artifactPath, bundle, optionsBuilder.build());

            getLog().info("Signature verified for " + artifactPath);
        } catch (BundleParseException
                | IOException
                | KeylessVerificationException
                | GeneralSecurityException
                | SigstoreConfigurationException e) {
            throw new MojoExecutionException(
                    "Signature verification failed for " + artifactPath + ": " + e.getMessage(), e);
        }
    }

    private String resolvePackageRef(String packageRef, LockFile lock)
            throws MojoExecutionException {
        String namespace = packageRef;
        String version = null;
        int atIndex = packageRef.indexOf('@');
        if (atIndex >= 0) {
            namespace = packageRef.substring(0, atIndex);
            version = packageRef.substring(atIndex + 1);
        }

        int colonIndex = namespace.indexOf(':');
        if (colonIndex < 0) {
            throw new MojoExecutionException(
                    "Invalid packageRef (expected namespace:name[@version]): " + packageRef);
        }

        String ns = namespace.substring(0, colonIndex);
        String name = namespace.substring(colonIndex + 1);

        String configToml = loadConfigToml();
        String registry = lock.resolveNamespace(configToml, ns);
        if (registry == null) {
            throw new MojoExecutionException(
                    "No registry found for namespace '" + ns + "' in config.toml");
        }

        String ref = registry + "/" + ns + "/" + name;
        if (version != null) {
            ref = ref + ":" + version;
        }

        getLog().info("Resolved " + packageRef + " -> " + ref);
        return ref;
    }

    private String loadConfigToml() throws MojoExecutionException {
        if (configFile != null && configFile.exists()) {
            try {
                return Files.readString(configFile.toPath());
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to read config file: " + configFile, e);
            }
        }

        Path defaultConfig = defaultConfigPath();
        if (defaultConfig != null && Files.exists(defaultConfig)) {
            try {
                return Files.readString(defaultConfig);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to read config file: " + defaultConfig, e);
            }
        }

        return "";
    }

    private static Path defaultConfigPath() {
        String xdgConfig = System.getenv("XDG_CONFIG_HOME");
        if (xdgConfig != null && !xdgConfig.isEmpty()) {
            return Path.of(xdgConfig, "wasm-pkg", "config.toml");
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            return Path.of(home, ".config", "wasm-pkg", "config.toml");
        }
        return null;
    }

    private File defaultOutputFile(String imageRef) {
        String filename = imageRef.substring(imageRef.lastIndexOf('/') + 1);
        int tagSep = filename.indexOf(':');
        if (tagSep > 0) {
            filename = filename.substring(0, tagSep);
        }
        int digestSep = filename.indexOf('@');
        if (digestSep > 0) {
            filename = filename.substring(0, digestSep);
        }
        filename = filename + ".wasm";
        return new File(project.getBuild().getDirectory() + "/wasm/" + filename);
    }

    private InlayClient createClient(String imageRef) throws MojoExecutionException {
        int slashIndex = imageRef.indexOf('/');
        if (slashIndex < 0) {
            throw new MojoExecutionException(
                    "Invalid imageRef (expected registry/repository[:tag]): " + imageRef);
        }
        String registryHost = imageRef.substring(0, slashIndex);

        InlayClient.Builder builder = InlayClient.builder();
        if (noCache) {
            builder.noCache();
        }
        if (insecure) {
            builder.insecure();
        }

        Server server = settings.getServer(registryHost);
        if (server != null && server.getUsername() != null && server.getPassword() != null) {
            getLog().debug("Using credentials from settings.xml for " + registryHost);
            builder.withCredentials(server.getUsername(), server.getPassword());
        }

        return builder.build();
    }
}
