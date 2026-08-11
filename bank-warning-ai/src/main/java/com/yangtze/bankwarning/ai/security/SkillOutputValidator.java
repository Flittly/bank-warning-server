package com.yangtze.bankwarning.ai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Skill 输出契约校验器（阶段三 · 输出校验）。
 *
 * 按 SKILL.md frontmatter 声明的 output 契约校验脚本 stdout：
 *   - text：默认契约，仅要求未超限截断；
 *   - json：要求输出是合法 JSON，且不能为空。
 * 校验失败一律按执行失败处理（fail-closed），绝不把不可信输出透传给下游。
 */
@Component
public class SkillOutputValidator {

    private static final Logger log = LoggerFactory.getLogger(SkillOutputValidator.class);

    private final ObjectMapper objectMapper;

    public SkillOutputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param metadata skill 元数据（决定输出契约），可为 null，按 text 处理
     * @param result   沙箱执行结果
     * @return 校验结果
     */
    public ValidationResult validate(SkillMetadata metadata, SkillSandboxExecutor.SandboxResult result) {
        if (result == null || !result.isSuccess()) {
            return ValidationResult.invalid("脚本执行未成功，无法校验输出");
        }
        // 截断的输出不可信，fail-closed
        if (result.isOutputTruncated()) {
            return ValidationResult.invalid("脚本输出超过上限被截断，结果不可信");
        }
        String output = metadata == null ? SkillMetadata.OUTPUT_TEXT : metadata.getOutput();
        if (SkillMetadata.OUTPUT_JSON.equals(output)) {
            String text = result.getStdout() == null ? "" : result.getStdout().strip();
            if (text.isEmpty()) {
                return ValidationResult.invalid("输出为空，不满足 json 契约");
            }
            try {
                objectMapper.readTree(text);
                return ValidationResult.valid();
            } catch (Exception e) {
                log.warn("[skill-output] 输出不是合法 JSON: {}", e.getMessage());
                return ValidationResult.invalid("输出不是合法 JSON: " + e.getMessage());
            }
        }
        return ValidationResult.valid();
    }

    /** 校验结果 */
    public static final class ValidationResult {
        private final boolean valid;
        private final String reason;

        private ValidationResult(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, "");
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }

        public boolean isValid() {
            return valid;
        }

        public String getReason() {
            return reason;
        }
    }
}
