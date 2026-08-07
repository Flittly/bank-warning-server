package com.yangtze.bankwarning.ai.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 临时冒烟：对仓库内真实存量 skills 目录跑静态扫描，确认 skill-creator 靠 permissions 放行、
 * 其余目录无黑名单误报。非安全组件长期用例，可随时删除。
 */
class RealSkillsSmokeTest {

    private final PythonImportScanner scanner = new PythonImportScanner(null, true);

    @Test
    void scanRealSkillsTree() throws IOException {
        Path skillsRoot = Path.of("src", "main", "resources", "skills").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(skillsRoot), "skills dir exists: " + skillsRoot);

        List<String> violations = new ArrayList<>();
        List<Path> pyFiles;
        try (Stream<Path> stream = Files.walk(skillsRoot)) {
            pyFiles = stream.filter(p -> p.getFileName().toString().endsWith(".py")).toList();
        }
        for (Path py : pyFiles) {
            Set<String> perms = permissionsFor(py.getParent(), skillsRoot);
            List<String> v = scanner.scanFile(py, perms);
            violations.addAll(v);
        }
        assertTrue(violations.isEmpty(), "unexpected violations: " + violations);
    }

    private static Set<String> permissionsFor(Path skillDir, Path skillsRoot) {
        Path cur = skillDir;
        while (cur != null && cur.startsWith(skillsRoot)) {
            Path md = cur.resolve("SKILL.md");
            if (Files.isRegularFile(md)) {
                try {
                    return PythonImportScanner.parsePermissions(Files.readString(md));
                } catch (IOException ignored) {
                    return Set.of();
                }
            }
            cur = cur.getParent();
        }
        return Set.of();
    }
}
