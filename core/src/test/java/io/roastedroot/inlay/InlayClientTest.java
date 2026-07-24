package io.roastedroot.inlay;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InlayClientTest {

    @TempDir Path tempDir;

    @Test
    void pullNullImageRefThrows() {
        InlayClient client = InlayClient.builder().noCache().build();
        InlayException ex =
                assertThrows(
                        InlayException.class, () -> client.pull(null, tempDir.resolve("out.wasm")));
        assertTrue(ex.getMessage().contains("imageRef"));
    }

    @Test
    void pullEmptyImageRefThrows() {
        InlayClient client = InlayClient.builder().noCache().build();
        InlayException ex =
                assertThrows(
                        InlayException.class, () -> client.pull("", tempDir.resolve("out.wasm")));
        assertTrue(ex.getMessage().contains("imageRef"));
    }

    @Test
    void pullNullOutputFileThrows() {
        InlayClient client = InlayClient.builder().noCache().build();
        assertThrows(InlayException.class, () -> client.pull("ghcr.io/test/foo:v1", null));
    }

    @Test
    void getDigestNullImageRefThrows() {
        InlayClient client = InlayClient.builder().noCache().build();
        assertThrows(InlayException.class, () -> client.getDigest(null));
    }

    @Test
    void builderDefaultsProducesClient() {
        InlayClient client = InlayClient.builder().build();
        assertNotNull(client);
    }

    @Test
    void builderInsecureProducesClient() {
        InlayClient client = InlayClient.builder().insecure().build();
        assertNotNull(client);
    }

    @Test
    void builderNoCacheProducesClient() {
        InlayClient client = InlayClient.builder().noCache().build();
        assertNotNull(client);
    }

    @Test
    void builderWithCacheDirProducesClient() {
        InlayClient client = InlayClient.builder().withCacheDir(tempDir).build();
        assertNotNull(client);
    }
}
