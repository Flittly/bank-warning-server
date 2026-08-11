package com.yangtze.bankwarning.ai.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillMetadataTest {

    @Test
    void parsesFullFrontmatter() {
        String md = """
                ---
                name: pdf
                version: 1.2.0
                output: json
                permissions: [network, subprocess]
                ---
                正文
                """;
        SkillMetadata meta = SkillMetadata.parse(md);
        assertEquals("pdf", meta.getName());
        assertEquals("1.2.0", meta.getVersion());
        assertEquals("json", meta.getOutput());
        assertTrue(meta.isJsonOutput());
        assertTrue(meta.getPermissions().contains("network"));
        assertTrue(meta.getPermissions().contains("subprocess"));
    }

    @Test
    void parsesListFormPermissions() {
        String md = """
                ---
                name: demo
                version: 2.0.0
                permissions:
                  - network
                  - process
                ---
                """;
        SkillMetadata meta = SkillMetadata.parse(md);
        assertTrue(meta.getPermissions().contains("network"));
        assertTrue(meta.getPermissions().contains("process"));
        assertEquals(2, meta.getPermissions().size());
    }

    @Test
    void missingFieldsUseSafeDefaults() {
        SkillMetadata meta = SkillMetadata.parse("no frontmatter here");
        assertEquals("", meta.getName());
        assertEquals(SkillMetadata.DEFAULT_VERSION, meta.getVersion());
        assertEquals(SkillMetadata.OUTPUT_TEXT, meta.getOutput());
        assertFalse(meta.isJsonOutput());
        assertTrue(meta.getPermissions().isEmpty());
    }

    @Test
    void outputIsNormalizedToLowerCase() {
        SkillMetadata meta = SkillMetadata.parse("---\nname: x\noutput: JSON\n---\n");
        assertEquals("json", meta.getOutput());
        assertTrue(meta.isJsonOutput());
    }
}
