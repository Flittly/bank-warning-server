package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.security.PythonImportScanner;
import com.yangtze.bankwarning.ai.store.SkillVersionStore;
import com.yangtze.bankwarning.ai.store.SkillVersionStore.SkillVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillVersionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void registerDownloadsKeepsMultipleVersionsAndActivatesLatest() {
        SkillVersionService svc = newService();

        svc.registerDownload("pdf", "1.0.0", "nacos", files("1.0.0"), "admin");
        svc.registerDownload("pdf", "2.0.0", "nacos", files("2.0.0"), "admin");

        // 新版本自动激活，旧版本降级 RETIRED，但目录共存
        assertEquals("2.0.0", svc.resolveActiveVersion("pdf").orElse(""));
        assertEquals("RETIRED", svc.findByVersion("pdf", "1.0.0").orElseThrow().status());
        assertTrue(svc.findByVersion("pdf", "1.0.0").isPresent());
        assertEquals(2, svc.listVersions("pdf").size());
    }

    @Test
    void activateSwitchesActiveVersionAndResolutionFollows() {
        SkillVersionService svc = newService();
        svc.registerDownload("pdf", "1.0.0", "nacos", files("1.0.0"), "admin");
        svc.registerDownload("pdf", "2.0.0", "nacos", files("2.0.0"), "admin");

        assertTrue(svc.activate("pdf", "1.0.0", "admin"));
        assertEquals("1.0.0", svc.resolveActiveVersion("pdf").orElse(""));
        Path activeDir = svc.resolveActiveDir("pdf").orElseThrow();
        assertEquals(tempDir.resolve("pdf").resolve("1.0.0"), activeDir);
        assertEquals("RETIRED", svc.findByVersion("pdf", "2.0.0").orElseThrow().status());
    }

    @Test
    void quarantineAndUnquarantineToggleStatus() {
        SkillVersionService svc = newService();
        svc.registerDownload("search", "1.0.0", "nacos", files("1.0.0"), "admin");

        assertTrue(svc.quarantine("search", "1.0.0", "admin"));
        assertEquals("QUARANTINED", svc.findByVersion("search", "1.0.0").orElseThrow().status());

        assertTrue(svc.unquarantine("search", "1.0.0", "admin"));
        assertEquals("ACTIVE", svc.findByVersion("search", "1.0.0").orElseThrow().status());
    }

    @Test
    void deleteRemovesRecordAndCacheDir() {
        SkillVersionService svc = newService();
        svc.registerDownload("pdf", "1.0.0", "nacos", files("1.0.0"), "admin");
        assertTrue(svc.delete("pdf", "1.0.0", "admin"));
        assertFalse(svc.findByVersion("pdf", "1.0.0").isPresent());
        assertFalse(tempDir.resolve("pdf").resolve("1.0.0").resolve("SKILL.md").toFile().exists());
    }

    @Test
    void normalizeVersionRejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillCacheService.normalizeVersion("../evil"));
        assertThrows(IllegalArgumentException.class,
                () -> SkillCacheService.normalizeVersion("1.0/0"));
        assertEquals("1.0.0", SkillCacheService.normalizeVersion(" 1.0.0 "));
    }

    private SkillVersionService newService() {
        SkillCacheService cacheService = new SkillCacheService(
                tempDir.toString(), new PythonImportScanner(List.of(), true));
        return new SkillVersionService(new MemoryVersionStore(), cacheService);
    }

    private static Map<String, byte[]> files(String version) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", ("---\nname: pdf\nversion: " + version + "\noutput: text\npermissions: []\n---\n")
                .getBytes(StandardCharsets.UTF_8));
        files.put("scripts/hello.py", "print('hello')\n".getBytes(StandardCharsets.UTF_8));
        return files;
    }

    /** 内存版版本存储：与 JdbcSkillVersionStore 行为对齐 */
    private static final class MemoryVersionStore implements SkillVersionStore {
        private final Map<String, SkillVersion> rows = new LinkedHashMap<>();
        private long seq = 0;

        private static String key(String skillName, String version) {
            return skillName + "@" + version;
        }

        @Override
        public Optional<SkillVersion> findActive(String skillName) {
            return rows.values().stream()
                    .filter(v -> v.skillName().equals(skillName) && STATUS_ACTIVE.equals(v.status()))
                    .findFirst();
        }

        @Override
        public Optional<SkillVersion> findByVersion(String skillName, String version) {
            return Optional.ofNullable(rows.get(key(skillName, version)));
        }

        @Override
        public List<SkillVersion> listVersions(String skillName) {
            return new ArrayList<>(rows.values().stream()
                    .filter(v -> v.skillName().equals(skillName))
                    .toList());
        }

        @Override
        public List<SkillVersion> listAll() {
            return new ArrayList<>(rows.values());
        }

        @Override
        public SkillVersion registerOrActivate(String skillName, String version, String source, String updatedBy) {
            rows.put(key(skillName, version), new SkillVersion(
                    ++seq, skillName, version, source, STATUS_ACTIVE, "now", "now", updatedBy, "now"));
            deactivateOthers(skillName, version);
            return rows.get(key(skillName, version));
        }

        @Override
        public boolean activate(String skillName, String version, String updatedBy) {
            SkillVersion current = rows.get(key(skillName, version));
            if (current == null) {
                return false;
            }
            rows.put(key(skillName, version), new SkillVersion(
                    current.id(), skillName, version, current.source(), STATUS_ACTIVE,
                    current.downloadedAt(), "now", updatedBy, "now"));
            deactivateOthers(skillName, version);
            return true;
        }

        @Override
        public boolean setStatus(String skillName, String version, String status, String updatedBy) {
            SkillVersion current = rows.get(key(skillName, version));
            if (current == null) {
                return false;
            }
            rows.put(key(skillName, version), new SkillVersion(
                    current.id(), skillName, version, current.source(), status,
                    current.downloadedAt(), current.activatedAt(), updatedBy, "now"));
            return true;
        }

        @Override
        public boolean delete(String skillName, String version) {
            return rows.remove(key(skillName, version)) != null;
        }

        private void deactivateOthers(String skillName, String version) {
            rows.forEach((k, v) -> {
                if (v.skillName().equals(skillName) && !v.version().equals(version)
                        && STATUS_ACTIVE.equals(v.status())) {
                    rows.put(k, new SkillVersion(
                            v.id(), v.skillName(), v.version(), v.source(), STATUS_RETIRED,
                            v.downloadedAt(), v.activatedAt(), v.updatedBy(), "now"));
                }
            });
        }
    }
}
