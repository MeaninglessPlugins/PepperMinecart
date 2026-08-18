package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/** 构建产物契约：交付 jar 必须携带重定位后的 bstats（org.bstats → com.pepperminecart.libs.bstats）。 */
class BuildArtifactContractTest {

    @Test
    void shadowJarContainsRelocatedBstatsAndDoesNotCollideWithThinJar() throws IOException {
        Path fat = Path.of("build/libs/PepperMinecart-1.0.0-all.jar").toAbsolutePath();
        Path thin = Path.of("build/libs/PepperMinecart-1.0.0.jar").toAbsolutePath();

        assertTrue(Files.exists(thin), "应保留普通 jar（瘦包）产物: " + thin);
        assertTrue(Files.exists(fat), "shadowJar 应使用 -all 分类器，避免与瘦包同名互相覆盖: " + fat);
        assertTrue(containsRelocatedBstats(fat), "shadowJar 应包含 com/pepperminecart/libs/bstats 下的重定位类");
        assertFalse(containsRelocatedBstats(thin), "普通 jar 不应包含 shadowJar 重定位类（否则两者输出路径发生冲突）");
    }

    private static boolean containsRelocatedBstats(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().startsWith("com/pepperminecart/libs/bstats/")) {
                    return true;
                }
            }
        }
        return false;
    }
}
