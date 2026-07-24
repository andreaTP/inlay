package io.roastedroot.inlay;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.wasi.WasiPreview1;

final class WkgParser implements AutoCloseable {

    private static final run.endive.wasm.WasmModule MODULE = WkgModule.load();

    private final WasiPreview1 wasi;
    private final WkgParser_ModuleExports exports;
    private final Memory memory;

    WkgParser() {
        this.wasi = WasiPreview1.builder().build();
        Instance instance =
                Instance.builder(MODULE)
                        .withMachineFactory(WkgModule::create)
                        .withImportValues(
                                ImportValues.builder().addFunction(wasi.toHostFunctions()).build())
                        .withStart(false)
                        .build();
        this.exports = new WkgParser_ModuleExports(instance);
        this.memory = exports.memory();
    }

    List<LockedPackage> readLock(String toml) {
        byte[] tomlBytes = toml.getBytes(StandardCharsets.UTF_8);
        int ptr = allocAndWrite(tomlBytes);
        try {
            long packed = exports.readLock(ptr, tomlBytes.length);
            if (packed == 0) {
                return List.of();
            }
            int resultPtr = (int) (packed >>> 32);
            int resultLen = (int) (packed & 0xFFFFFFFFL);
            byte[] data = memory.readBytes(resultPtr, resultLen);
            exports.dealloc(resultPtr, resultLen);

            String asString = new String(data, StandardCharsets.UTF_8);
            if (asString.startsWith("ERROR:")) {
                throw new InlayException("Failed to parse lock file: " + asString.substring(6));
            }

            return decodeEntries(data);
        } finally {
            exports.dealloc(ptr, tomlBytes.length);
        }
    }

    String writeLock(List<LockedPackage> packages) {
        byte[] data = encodeEntries(packages);
        int ptr = allocAndWrite(data);
        try {
            long packed = exports.writeLock(ptr, data.length);
            String result = readString(packed);
            if (result.startsWith("ERROR:")) {
                throw new InlayException("Failed to write lock file: " + result.substring(6));
            }
            return result;
        } finally {
            exports.dealloc(ptr, data.length);
        }
    }

    String resolveNamespace(String configToml, String namespace) {
        byte[] configBytes = configToml.getBytes(StandardCharsets.UTF_8);
        byte[] nsBytes = namespace.getBytes(StandardCharsets.UTF_8);

        int configPtr = allocAndWrite(configBytes);
        int nsPtr = allocAndWrite(nsBytes);
        try {
            long packed =
                    exports.resolveNamespace(configPtr, configBytes.length, nsPtr, nsBytes.length);
            String result = readString(packed);
            return result.isEmpty() ? null : result;
        } finally {
            exports.dealloc(configPtr, configBytes.length);
            exports.dealloc(nsPtr, nsBytes.length);
        }
    }

    @Override
    public void close() {
        if (wasi != null) {
            wasi.close();
        }
    }

    private int allocAndWrite(byte[] data) {
        int ptr = exports.alloc(data.length);
        memory.write(ptr, data);
        return ptr;
    }

    private String readString(long packed) {
        if (packed == 0) {
            return "";
        }
        int resultPtr = (int) (packed >>> 32);
        int resultLen = (int) (packed & 0xFFFFFFFFL);
        byte[] resultBytes = memory.readBytes(resultPtr, resultLen);
        exports.dealloc(resultPtr, resultLen);
        return new String(resultBytes, StandardCharsets.UTF_8);
    }

    private static List<LockedPackage> decodeEntries(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int count = buf.getInt();
        List<LockedPackage> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = readPrefixedString(buf);
            String registry = readPrefixedString(buf);
            String requirement = readPrefixedString(buf);
            String version = readPrefixedString(buf);
            String digest = readPrefixedString(buf);
            result.add(
                    new LockedPackage(
                            name,
                            registry.isEmpty() ? null : registry,
                            requirement,
                            version,
                            digest));
        }
        return result;
    }

    private static byte[] encodeEntries(List<LockedPackage> packages) {
        var out = new ByteArrayOutputStream();
        writeI32(out, packages.size());
        for (LockedPackage p : packages) {
            writePrefixedString(out, p.getName());
            writePrefixedString(out, p.getRegistry() != null ? p.getRegistry() : "");
            writePrefixedString(out, p.getRequirement());
            writePrefixedString(out, p.getVersion());
            writePrefixedString(out, p.getDigest());
        }
        return out.toByteArray();
    }

    private static String readPrefixedString(ByteBuffer buf) {
        int len = buf.getInt();
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeI32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writePrefixedString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeI32(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }
}
