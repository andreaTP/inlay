package io.roastedroot.inlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WkgParserTest {

    private WkgParser parser;

    @BeforeEach
    void setUp() {
        parser = new WkgParser();
    }

    @AfterEach
    void tearDown() {
        if (parser != null) {
            parser.close();
        }
    }

    @Test
    void resolveNamespace() {
        String config = "[namespace_registries]\n" + "roastedroot = \"ghcr.io\"\n";
        assertEquals("ghcr.io", parser.resolveNamespace(config, "roastedroot"));
    }

    @Test
    void resolveNamespaceFallbackWasi() {
        assertEquals("wasi.dev", parser.resolveNamespace("", "wasi"));
    }

    @Test
    void resolveNamespaceUnknownReturnsNull() {
        assertNull(parser.resolveNamespace("", "unknown"));
    }

    @Test
    void lockRoundTrip() {
        List<LockedPackage> packages =
                List.of(
                        new LockedPackage(
                                "roastedroot:sqlite4j-wasm",
                                "ghcr.io",
                                "=3.51.0",
                                "3.51.0",
                                "sha256:abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"));

        String toml = parser.writeLock(packages);
        assertNotNull(toml);
        assertTrue(toml.contains("[[packages]]"));

        List<LockedPackage> parsed = parser.readLock(toml);
        assertEquals(1, parsed.size());
        assertEquals("roastedroot:sqlite4j-wasm", parsed.get(0).getName());
        assertEquals("ghcr.io", parsed.get(0).getRegistry());
        assertEquals("3.51.0", parsed.get(0).getVersion());
    }

    @Test
    void readLockMalformedTomlThrows() {
        assertThrows(InlayException.class, () -> parser.readLock("this is not valid toml [[["));
    }

    @Test
    void readLockEmptyTomlReturnsEmpty() {
        List<LockedPackage> result = parser.readLock("version = 1\n");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void writeLockEmptyListProducesValidToml() {
        String toml = parser.writeLock(List.of());
        assertNotNull(toml);
        assertTrue(toml.contains("version = 1"));
    }

    @Test
    void writeLockLongFieldsWork() {
        String longRegistry = "very-long-registry-host.example.com:8443";
        String longDigest = "sha256:" + "abcdef1234567890".repeat(4);
        List<LockedPackage> packages =
                List.of(
                        new LockedPackage(
                                "org:module", longRegistry, "=1.0.0", "1.0.0", longDigest));

        String toml = parser.writeLock(packages);
        assertNotNull(toml);

        List<LockedPackage> parsed = parser.readLock(toml);
        assertEquals(1, parsed.size());
        assertEquals(longRegistry, parsed.get(0).getRegistry());
        assertEquals(longDigest, parsed.get(0).getDigest());
    }
}
