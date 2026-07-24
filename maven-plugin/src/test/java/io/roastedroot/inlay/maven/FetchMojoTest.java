package io.roastedroot.inlay.maven;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.settings.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FetchMojoTest {

    @TempDir Path tempDir;

    @Test
    void missingImageRefAndPackageRefFails() throws Exception {
        FetchMojo mojo = createMojo(new ModuleConfig());

        MojoExecutionException ex =
                assertThrows(MojoExecutionException.class, () -> mojo.execute());
        assertTrue(ex.getMessage().contains("imageRef or packageRef"));
    }

    @Test
    void invalidImageRefWithoutSlashFails() throws Exception {
        ModuleConfig module = new ModuleConfig();
        module.setImageRef("no-slash-here");
        FetchMojo mojo = createMojo(module);

        MojoExecutionException ex =
                assertThrows(MojoExecutionException.class, () -> mojo.execute());
        assertTrue(ex.getMessage().contains("Invalid imageRef"));
    }

    private FetchMojo createMojo(ModuleConfig module) throws Exception {
        FetchMojo mojo = new FetchMojo();
        setField(mojo, "modules", List.of(module));
        setField(mojo, "lockFile", tempDir.resolve("wkg.lock").toFile());
        setField(mojo, "update", false);
        setField(mojo, "noCache", true);
        setField(mojo, "settings", new Settings());
        return mojo;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
