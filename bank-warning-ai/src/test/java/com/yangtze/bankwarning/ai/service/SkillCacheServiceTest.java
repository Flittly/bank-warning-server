package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.security.PythonImportScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCacheServiceTest {

    @TempDir
    Path temp;

    private SkillCacheService service() {
        return new SkillCacheService(temp.toString(), new PythonImportScanner(null, true));
    }

    @Test
    void materializeWritesFilesToCacheAndKeepsLayout() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: demo\n---\n".getBytes(StandardCharsets.UTF_8));
        files.put("scripts/hello.py", "print('hi')\n".getBytes(StandardCharsets.UTF_8));

        Path dir = service().materialize("demo", files);

        assertTrue(Files.isRegularFile(dir.resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(dir.resolve("scripts/hello.py")));
        assertEquals("print('hi')\n", Files.readString(dir.resolve("scripts/hello.py")));
    }

    @Test
    void binaryAssetSurvivesByteExact() throws Exception {
        byte[] binary = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x00, (byte) 0xFF, 0x1A};
        Map<String, String> resources = Map.of(
                "assets/a.bin", "base64:" + Base64.getEncoder().encodeToString(binary));

        Path dir = service().materializeResources("demo", resources);

        assertArrayEquals(binary, Files.readAllBytes(dir.resolve("assets/a.bin")));
    }

    @Test
    void pathTraversalIsRejected() {
        Map<String, byte[]> files = Map.of(
                "../evil.txt", "x".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> service().materialize("demo", files));
    }

    @Test
    void versionSortIsNatural() {
        assertTrue(SkillCacheService.compareVersions("1.10.0", "1.9.0") > 0);
        assertTrue(SkillCacheService.compareVersions("1.9.0", "1.10.0") < 0);
        assertEquals(0, SkillCacheService.compareVersions("1.0", "1.0.0"));
    }
}
