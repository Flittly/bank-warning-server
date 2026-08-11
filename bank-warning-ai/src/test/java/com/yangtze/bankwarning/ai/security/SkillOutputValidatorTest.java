package com.yangtze.bankwarning.ai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillOutputValidatorTest {

    private final SkillOutputValidator validator = new SkillOutputValidator(new ObjectMapper());

    private static SkillSandboxExecutor.SandboxResult result(String stdout, boolean truncated) {
        return new SkillSandboxExecutor.SandboxResult(0, stdout, "", false, truncated, "PROCESS", 10L);
    }

    @Test
    void textOutputIsValid() {
        SkillMetadata meta = SkillMetadata.parse("---\nname: pdf\n---\n");
        assertTrue(validator.validate(meta, result("提取到的文本", false)).isValid());
    }

    @Test
    void jsonOutputValidWhenParsable() {
        SkillMetadata meta = SkillMetadata.parse("---\nname: demo\noutput: json\n---\n");
        assertTrue(validator.validate(meta, result("{\"ok\": true, \"n\": 3}", false)).isValid());
    }

    @Test
    void jsonOutputRejectedWhenNotParsable() {
        SkillMetadata meta = SkillMetadata.parse("---\nname: demo\noutput: json\n---\n");
        SkillOutputValidator.ValidationResult r =
                validator.validate(meta, result("not a json at all", false));
        assertFalse(r.isValid());
        assertTrue(r.getReason().contains("JSON"));
    }

    @Test
    void jsonOutputRejectedWhenEmpty() {
        SkillMetadata meta = SkillMetadata.parse("---\nname: demo\noutput: json\n---\n");
        assertFalse(validator.validate(meta, result("   ", false)).isValid());
    }

    @Test
    void truncatedOutputRejectedEvenForText() {
        SkillMetadata meta = SkillMetadata.parse("---\nname: pdf\n---\n");
        assertFalse(validator.validate(meta, result("部分输出", true)).isValid());
    }

    @Test
    void failedExecutionIsRejected() {
        SkillMetadata meta = SkillMetadata.parse("---\nname: pdf\n---\n");
        SkillSandboxExecutor.SandboxResult failed =
                new SkillSandboxExecutor.SandboxResult(1, "", "boom", false, false, "PROCESS", 10L);
        assertFalse(validator.validate(meta, failed).isValid());
    }
}
