package io.roastedroot.inlay;

import land.oras.ContainerRef;

public final class LockedPackage {

    private final String name;
    private final String registry;
    private final String requirement;
    private final String version;
    private final String digest;

    public LockedPackage(
            String name, String registry, String requirement, String version, String digest) {
        this.name = name;
        this.registry = registry;
        this.requirement = requirement;
        this.version = version;
        this.digest = digest;
    }

    static LockedPackage fromImageRef(String imageRef, String digest) {
        ContainerRef ref = ContainerRef.parse(imageRef);
        String repo = ref.getRepository();
        String ns = ref.getNamespace();
        String name;
        if (ns != null && !ns.isEmpty()) {
            name = ns + ":" + repo;
        } else {
            name = ref.getRegistry() + ":" + repo;
        }
        String tag = ref.getTag() != null ? ref.getTag() : "0.0.0";
        return new LockedPackage(name, ref.getRegistry(), "=" + tag, tag, digest);
    }

    public String getName() {
        return name;
    }

    public String getRegistry() {
        return registry;
    }

    public String getRequirement() {
        return requirement;
    }

    public String getVersion() {
        return version;
    }

    public String getDigest() {
        return digest;
    }

    @Override
    public String toString() {
        return name + "@" + version + " (" + digest + ")";
    }
}
