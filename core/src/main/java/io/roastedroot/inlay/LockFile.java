package io.roastedroot.inlay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LockFile {

    private static final WkgParser PARSER = new WkgParser();

    private final List<LockedPackage> packages;

    public LockFile() {
        this.packages = new ArrayList<>();
    }

    public LockFile(List<LockedPackage> packages) {
        this.packages = new ArrayList<>(packages);
    }

    public List<LockedPackage> getPackages() {
        return Collections.unmodifiableList(packages);
    }

    public LockedPackage findByImageRef(String imageRef) {
        LockedPackage probe = LockedPackage.fromImageRef(imageRef, "");
        for (LockedPackage p : packages) {
            if (p.getName().equals(probe.getName()) && probe.getVersion().equals(p.getVersion())) {
                return p;
            }
        }
        return null;
    }

    public void addOrUpdate(String imageRef, String digest) {
        LockedPackage pkg = LockedPackage.fromImageRef(imageRef, digest);

        for (int i = 0; i < packages.size(); i++) {
            LockedPackage existing = packages.get(i);
            if (existing.getName().equals(pkg.getName())
                    && existing.getVersion().equals(pkg.getVersion())) {
                packages.set(i, pkg);
                return;
            }
        }
        packages.add(pkg);
    }

    public void write(Path path) throws IOException {
        String toml;
        synchronized (PARSER) {
            toml = PARSER.writeLock(packages);
        }
        Files.writeString(path, toml);
    }

    public static LockFile read(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new LockFile();
        }
        String toml = Files.readString(path);
        List<LockedPackage> packages;
        synchronized (PARSER) {
            packages = PARSER.readLock(toml);
        }
        return new LockFile(packages);
    }

    public static String resolveNamespace(String configToml, String namespace) {
        synchronized (PARSER) {
            return PARSER.resolveNamespace(configToml, namespace);
        }
    }
}
