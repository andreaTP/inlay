package io.roastedroot.inlay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LockFile {

    private final WkgParser parser;
    private final List<LockedPackage> packages;

    public LockFile(WkgParser parser) {
        this.parser = parser;
        this.packages = new ArrayList<>();
    }

    public LockFile(WkgParser parser, List<LockedPackage> packages) {
        this.parser = parser;
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
        String toml = parser.writeLock(packages);
        Files.writeString(path, toml);
    }

    public String resolveNamespace(String configToml, String namespace) {
        return parser.resolveNamespace(configToml, namespace);
    }

    public static LockFile read(WkgParser parser, Path path) throws IOException {
        if (!Files.exists(path)) {
            return new LockFile(parser);
        }
        String toml = Files.readString(path);
        List<LockedPackage> packages = parser.readLock(toml);
        return new LockFile(parser, packages);
    }
}
