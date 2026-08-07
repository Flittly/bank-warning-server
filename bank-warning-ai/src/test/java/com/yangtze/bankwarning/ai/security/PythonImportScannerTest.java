package com.yangtze.bankwarning.ai.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonImportScannerTest {

    private final PythonImportScanner scanner = new PythonImportScanner(null, true);

    @Test
    void scansPlainImport() {
        List<String> violations = scanner.scanFile(
                tempPy("import socket\nprint('x')\n"), Set.of());
        assertTrue(violations.stream().anyMatch(v -> v.contains("socket")));
    }

    @Test
    void scansFromImport() {
        List<String> violations = scanner.scanFile(
                tempPy("from subprocess import run\n"), Set.of());
        assertTrue(violations.stream().anyMatch(v -> v.contains("subprocess")));
    }

    @Test
    void allowsSafeImports() {
        List<String> violations = scanner.scanFile(
                tempPy("import sys\nfrom pypdf import PdfReader\nimport json\n"), Set.of());
        assertTrue(violations.isEmpty());
    }

    @Test
    void permissionsAllowSubprocess() {
        List<String> violations = scanner.scanFile(
                tempPy("import subprocess\nimport sys\n"), Set.of("subprocess"));
        assertTrue(violations.isEmpty());
    }

    @Test
    void permissionsNetworkAllowsHttp() {
        List<String> violations = scanner.scanFile(
                tempPy("from http.server import HTTPServer\nimport socket\n"), Set.of("network"));
        assertTrue(violations.isEmpty());
    }

    @Test
    void urllibPrefixMatched() {
        List<String> violations = scanner.scanFile(
                tempPy("import urllib.request\n"), Set.of());
        assertTrue(violations.stream().anyMatch(v -> v.contains("urllib.request")));
    }

    @Test
    void parsePermissionsInlineArray() {
        Set<String> perms = PythonImportScanner.parsePermissions(
                "---\nname: x\npermissions: [network, subprocess]\n---\nbody");
        assertEquals(Set.of("network", "subprocess"), perms);
    }

    @Test
    void parsePermissionsBlockList() {
        Set<String> perms = PythonImportScanner.parsePermissions(
                "---\nname: x\npermissions:\n  - network\n  - subprocess\n---\nbody");
        assertEquals(Set.of("network", "subprocess"), perms);
    }

    @Test
    void parsePermissionsMissingReturnsEmpty() {
        assertTrue(PythonImportScanner.parsePermissions("no frontmatter here").isEmpty());
    }

    @Test
    void failOnViolationControlsAllowedFlag() {
        PythonImportScanner lenient = new PythonImportScanner(null, false);
        var result = lenient.scanSkillDir(tempSkillDir(), Set.of());
        assertTrue(result.isAllowed());
        assertFalse(result.getViolations().isEmpty());
    }

    private static java.nio.file.Path tempPy(String content) {
        try {
            java.nio.file.Path f = java.nio.file.Files.createTempFile("file", ".py");
            java.nio.file.Files.writeString(f, content);
            return f;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static java.nio.file.Path tempSkillDir() {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("skill-scan-");
            java.nio.file.Files.writeString(dir.resolve("a.py"), "import socket\n");
            return dir;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
