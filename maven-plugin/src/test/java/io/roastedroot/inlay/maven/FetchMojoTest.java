package io.roastedroot.inlay.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
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

    @Test
    void defaultOutputFileStandard() throws Exception {
        File result = invokeDefaultOutputFile("registry.io/ns/module:1.0.0");
        assertEquals("module.wasm", result.getName());
    }

    @Test
    void defaultOutputFileWithDigest() throws Exception {
        File result = invokeDefaultOutputFile("registry.io/ns/module@sha256:abc123");
        assertEquals("module.wasm", result.getName());
    }

    @Test
    void defaultOutputFileNoTag() throws Exception {
        File result = invokeDefaultOutputFile("registry.io/ns/module");
        assertEquals("module.wasm", result.getName());
    }

    @Test
    void defaultOutputFileDeeplyNested() throws Exception {
        File result = invokeDefaultOutputFile("registry.io/org/sub/module:v1");
        assertEquals("module.wasm", result.getName());
    }

    private File invokeDefaultOutputFile(String imageRef) throws Exception {
        FetchMojo mojo = createMojoWithProject();
        Method method = FetchMojo.class.getDeclaredMethod("defaultOutputFile", String.class);
        method.setAccessible(true);
        return (File) method.invoke(mojo, imageRef);
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

    private FetchMojo createMojoWithProject() throws Exception {
        FetchMojo mojo = createMojo(new ModuleConfig());
        MavenProject project = new MavenProject();
        Build build = new Build();
        build.setDirectory(tempDir.resolve("target").toString());
        project.setBuild(build);
        setField(mojo, "project", project);
        return mojo;
    }

    static void setField(Object target, String fieldName, Object value) throws Exception {
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
