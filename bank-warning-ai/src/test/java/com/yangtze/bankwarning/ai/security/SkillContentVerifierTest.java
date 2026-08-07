package com.yangtze.bankwarning.ai.security;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillContentVerifierTest {

    @Test
    void buildZipThenVerify_roundTrip_ok_withHmac() throws Exception {
        SkillContentVerifier verifier = new SkillContentVerifier("test-hmac-secret");
        byte[] zip = buildZip(verifier);
        assertTrue(verifier.verifyZip(zip));
    }

    @Test
    void buildZipThenVerify_ok_withoutHmacKey() throws Exception {
        SkillContentVerifier verifier = new SkillContentVerifier("");
        byte[] zip = buildZip(verifier);
        assertTrue(verifier.verifyZip(zip));
    }

    @Test
    void tamperedScriptFailsVerification() throws Exception {
        SkillContentVerifier verifier = new SkillContentVerifier("test-hmac-secret");
        byte[] zip = buildZip(verifier);
        // 篡改 scripts/extract.py 的一个字节
        byte[] tampered = tamper(zip, "scripts/extract.py");
        assertFalse(verifier.verifyZip(tampered));
    }

    @Test
    void tamperedHmacSignatureFailsVerification() throws Exception {
        SkillContentVerifier verifier = new SkillContentVerifier("test-hmac-secret");
        byte[] zip = buildZip(verifier);
        byte[] tampered = tamper(zip, SkillContentVerifier.CHECKSUM_ENTRY);
        assertFalse(verifier.verifyZip(tampered));
    }

    @Test
    void wrongKeyFailsVerification() throws Exception {
        SkillContentVerifier writer = new SkillContentVerifier("key-a");
        byte[] zip = buildZip(writer);
        SkillContentVerifier reader = new SkillContentVerifier("key-b");
        assertFalse(reader.verifyZip(zip));
    }

    @Test
    void zipWithoutManifestPasses() throws Exception {
        SkillContentVerifier verifier = new SkillContentVerifier("test-hmac-secret");
        byte[] zip;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            put(zos, "SKILL.md", "hello");
            put(zos, "scripts/a.py", "import sys");
        }
        zip = baos.toByteArray();
        assertTrue(verifier.verifyZip(zip));
    }

    @Test
    void verifyDir_detectsMissingFile() throws Exception {
        SkillContentVerifier verifier = new SkillContentVerifier("test-hmac-secret");
        byte[] zip = buildZip(verifier);
        Path dir = Files.createTempDirectory("skill-dir-");
        SkillContentVerifier.ZipContent content = verifier.parseZip(zip);
        for (Map.Entry<String, byte[]> e : content.files.entrySet()) {
            Path target = SkillPathGuard.safeResolve(dir, e.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, e.getValue());
        }
        Files.write(dir.resolve(SkillContentVerifier.CHECKSUM_ENTRY), content.checksumContent);
        // 删除清单中声明的一个文件
        Files.delete(SkillPathGuard.safeResolve(dir, "scripts/extract.py"));
        assertFalse(verifier.verifyDir(dir));
    }

    private static byte[] buildZip(SkillContentVerifier verifier) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Map<String, byte[]> contents = new LinkedHashMap<>();
        contents.put("SKILL.md", "---\nname: pdf\n---\nbody".getBytes(StandardCharsets.UTF_8));
        contents.put("scripts/extract.py", "import sys\nprint('x')".getBytes(StandardCharsets.UTF_8));
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> e : contents.entrySet()) {
                put(zos, e.getKey(), new String(e.getValue(), StandardCharsets.UTF_8));
            }
            verifier.writeChecksumEntry(zos, contents);
        }
        return baos.toByteArray();
    }

    private static void put(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static byte[] tamper(byte[] zip, String targetEntry) throws Exception {
        java.util.Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (name.isEmpty()) continue;
                byte[] data = zis.readAllBytes();
                if (name.equals(targetEntry) && data.length > 0) {
                    byte[] copy = data.clone();
                    copy[copy.length / 2] ^= 0x01;
                    data = copy;
                }
                entries.put(name, data);
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}
