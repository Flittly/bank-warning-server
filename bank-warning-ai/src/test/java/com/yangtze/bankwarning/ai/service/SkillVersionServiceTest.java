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

    /**
     * 回归测试（核心修复）：classpath skill 的 resources 按框架设计不含 SKILL.md，
     * 调用方必须把完整 SKILL.md（frontmatter + 正文）传进来。
     * 修好之前：版本退成 0.0.0、目录里没有 SKILL.md → WARN + 激活永久失败 + 权限读不到。
     */
    @Test
    void registerResourcesWritesSkillMdSoVersionMetadataAndActivationWork() {
        SkillVersionService svc = newService();

        // 模拟 ClasspathSkillRepository 给的资源：只有附件，没有 SKILL.md
        Map<String, String> resources = new LinkedHashMap<>();
        resources.put("scripts/hello.py", "print('hello')\n");

        String fullSkillMd = "---\nname: word\nversion: 0.1.0\noutput: text\n"
                + "permissions:\n  - network\n  - subprocess\n---\n\n# word\n正文\n";
        svc.registerResources("word", resources, fullSkillMd, "classpath", "system");

        // 1) 版本号从 SKILL.md frontmatter 解析出来，而不是退回默认值
        assertEquals("0.1.0", svc.resolveActiveVersion("word").orElse(""));

        // 2) 版本目录里真的有 SKILL.md —— 激活的前置判据就是它，所以能成功
        assertTrue(svc.activate("word", "0.1.0", "admin"), "版本目录含 SKILL.md 时激活应成功");

        // 3) 附件（脚本）也照常落盘，不只是说明书
        Path versionDir = svc.resolveActiveDir("word").orElseThrow();
        assertEquals(tempDir.resolve("word").resolve("0.1.0"), versionDir);
        assertTrue(versionDir.resolve("SKILL.md").toFile().exists());
        assertTrue(versionDir.resolve("scripts").resolve("hello.py").toFile().exists());

        // 4) 权限元数据可读 —— 「Skill 审批」页不再是瞎的
        assertTrue(PythonImportScanner.parsePermissions(versionDir).contains("network"));
        assertTrue(PythonImportScanner.parsePermissions(versionDir).contains("subprocess"));
    }

    /** resources 里已带 SKILL.md（下载包形态）时原样保留，不被运行期拼装版覆盖 */
    @Test
    void registerResourcesKeepsSkillMdAlreadyInResourcePack() {
        SkillVersionService svc = newService();

        Map<String, String> resources = new LinkedHashMap<>();
        resources.put("SKILL.md", "---\nname: pdf\nversion: 3.2.1\n---\n\n包内原样正文\n");
        resources.put("scripts/a.py", "print(1)\n");

        svc.registerResources("pdf", resources,
                "---\nname: pdf\nversion: 9.9.9\n---\n\n运行期拼装正文\n", "nacos", "system");

        assertEquals("3.2.1", svc.resolveActiveVersion("pdf").orElse(""),
                "resources 里已有的 SKILL.md 应优先，不被传入的拼装版覆盖");
    }

    /** find-skills 这类 jar 内只有 SKILL.md 的 skill：resources 为空 map，也必须建出版本目录 */
    @Test
    void registerResourcesCreatesVersionDirWhenOnlySkillMdPresent() {
        SkillVersionService svc = newService();

        svc.registerResources("find-skills", new LinkedHashMap<>(),
                "---\nname: find-skills\nversion: 0.0.1\n---\n\n正文\n", "classpath", "system");

        assertEquals("0.0.1", svc.resolveActiveVersion("find-skills").orElse(""));
        assertTrue(svc.activate("find-skills", "0.0.1", "admin"),
                "resources 为空时也应建出含 SKILL.md 的版本目录");
    }

    private SkillVersionService newService() {
        SkillCacheService cacheService = new SkillCacheService(
                tempDir.toString(), PythonImportScanner.of(List.of(), true));
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
