package com.yangtze.bankwarning.ai.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.Getter;

/**
 * Skill 元数据解析器（阶段三 · 治理闭环）。
 *
 * 从 SKILL.md 的 YAML frontmatter 中解析 name / version / output / permissions，
 * 供权限审批、版本锁定与输出契约校验使用。
 * 缺失的字段有安全默认值：version 视为 0.0.0，output 视为 text，permissions 为空。
 */
@Getter
public final class SkillMetadata {

    /** frontmatter 段落：--- 包裹的 YAML */
    private static final Pattern FRONTMATTER =
            Pattern.compile("^---\\s*\\R(.*?)\\R---\\s*", Pattern.DOTALL);

    public static final String DEFAULT_VERSION = "0.0.0";
    public static final String OUTPUT_TEXT = "text";
    public static final String OUTPUT_JSON = "json";

    private final String name;
    private final String version;
    private final String output;
    private final Set<String> permissions;

    private SkillMetadata(String name, String version, String output, Set<String> permissions) {
        this.name = name == null ? "" : name.strip();
        this.version = (version == null || version.isBlank()) ? DEFAULT_VERSION : version.strip();
        this.output = (output == null || output.isBlank()) ? OUTPUT_TEXT : output.strip().toLowerCase();
        this.permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public static SkillMetadata parse(Path skillDir) {
        if (skillDir == null) {
            return empty();
        }
        Path skillMd = skillDir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) {
            return empty();
        }
        try {
            return parse(Files.readString(skillMd, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return empty();
        }
    }

    public static SkillMetadata parse(String skillMdContent) {
        if (skillMdContent == null) {
            return empty();
        }
        Matcher fm = FRONTMATTER.matcher(skillMdContent);
        if (!fm.find()) {
            return empty();
        }
        String yaml = fm.group(1);
        return new SkillMetadata(
                field(yaml, "name"),
                field(yaml, "version"),
                field(yaml, "output"),
                parsePermissions(yaml));
    }

    public static SkillMetadata empty() {
        return new SkillMetadata("", DEFAULT_VERSION, OUTPUT_TEXT, Set.of());
    }

    /** 读取单个标量字段（去掉行内注释和首尾引号） */
    private static String field(String yaml, String key) {
        Matcher m = Pattern.compile("^\\s*" + key + "\\s*:\\s*([^\\n\\r#]+)", Pattern.MULTILINE).matcher(yaml);
        if (!m.find()) {
            return "";
        }
        String value = m.group(1).strip();
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash).strip();
        }
        return value.replaceAll("^[\"']|[\"']$", "");
    }

    /** 解析 permissions：支持行内数组 [a, b] 与列表两种形式 */
    private static Set<String> parsePermissions(String yaml) {
        Set<String> result = new LinkedHashSet<>();
        Matcher inline = Pattern.compile("^\\s*permissions\\s*:\\s*\\[([^\\]]*)\\]", Pattern.MULTILINE).matcher(yaml);
        if (inline.find()) {
            for (String item : inline.group(1).split(",")) {
                String t = item.strip().replaceAll("[\"'\\[\\]]", "");
                if (!t.isEmpty()) {
                    result.add(t.toLowerCase());
                }
            }
            return result;
        }
        Matcher block = Pattern.compile("^\\s*permissions\\s*:\\s*\\R", Pattern.MULTILINE).matcher(yaml);
        if (block.find()) {
            String after = yaml.substring(block.end());
            Matcher item = Pattern.compile("^\\s*-\\s*([^\\n\\r]+)", Pattern.MULTILINE).matcher(after);
            while (item.find()) {
                String t = item.group(1).strip().replaceAll("[\"'\\[\\]]", "");
                if (!t.isEmpty()) {
                    result.add(t.toLowerCase());
                }
            }
        }
        return result;
    }

    public boolean isJsonOutput() {
        return OUTPUT_JSON.equals(output);
    }
}
