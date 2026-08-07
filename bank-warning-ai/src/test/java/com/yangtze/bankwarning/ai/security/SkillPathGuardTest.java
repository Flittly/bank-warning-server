package com.yangtze.bankwarning.ai.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillPathGuardTest {

    private final Path base = Path.of(System.getProperty("java.io.tmpdir"))
            .resolve("skills-cache").resolve("pdf").toAbsolutePath().normalize();

    @Test
    void safeResolve_ok_normalRelativePath() {
        Path resolved = SkillPathGuard.safeResolve(base, "scripts/extract_text.py");
        assertTrue(resolved.startsWith(base));
        assertTrue(resolved.endsWith(Path.of("scripts", "extract_text.py")));
    }

    @Test
    void safeResolve_rejectsBackslashTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillPathGuard.safeResolve(base, "..\\..\\etc\\passwd"));
    }

    @Test
    void safeResolve_rejectsForwardSlashTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillPathGuard.safeResolve(base, "scripts/../../../tmp/x.py"));
    }

    @Test
    void safeResolve_rejectsAbsolutePath() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillPathGuard.safeResolve(base, "C:/windows/system32/cmd.exe"));
    }

    @Test
    void safeResolve_rejectsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillPathGuard.safeResolve(base, "   "));
    }

    @Test
    void isWithin_normalizesBothSides() {
        Path outside = Path.of("C:/skills-cache").toAbsolutePath().resolve("evil.py").normalize();
        assertTrue(!SkillPathGuard.isWithin(base, outside));
        assertTrue(SkillPathGuard.isWithin(base, base.resolve("a/b/c")));
    }

    @Test
    void safeResolve_rejectsParentEscape() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillPathGuard.safeResolve(base, "../pdf2/x.py"));
    }
}
