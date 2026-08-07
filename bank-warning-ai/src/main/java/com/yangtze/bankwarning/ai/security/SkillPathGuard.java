package com.yangtze.bankwarning.ai.security;

import java.nio.file.Path;

/**
 * 路径防逃逸守卫：所有基于用户可控相对路径解析目标路径的地方，
 * 必须经过 safeResolve 校验，禁止通过 ../ 逃逸出基础目录。
 */
public final class SkillPathGuard {

    private SkillPathGuard() {
    }

    /**
     * 在 base 下安全解析 relPath，防止路径穿越。
     *
     * @param base    基础目录（必须已 normalize）
     * @param relPath 用户可控的相对路径，可为空
     * @return normalize 后的目标路径，保证位于 base 内
     * @throws IllegalArgumentException 路径为绝对路径、或解析后逃逸出 base
     */
    public static Path safeResolve(Path base, String relPath) {
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("相对路径不能为空");
        }
        // 统一转为绝对路径并归一化，保证后续 startsWith 比较的基准一致
        Path baseNorm = base.toAbsolutePath().normalize();
        // Windows 反斜杠（..\）也视为分隔符，防止把路径当普通文件名绕过校验
        String normalizedRel = relPath.replace('\\', '/');
        Path candidate = baseNorm.resolve(normalizedRel).normalize();
        // 必须先 normalize 再 startsWith：base/../evil 词法前缀是 base 会误判，
        // normalize 还原成 ../evil 后才能识别出逃逸
        if (!candidate.startsWith(baseNorm)) {
            throw new IllegalArgumentException("非法路径穿越: " + relPath);
        }
        return candidate;
    }

    /**
     * 判断 target 是否位于 base 内（均先转为绝对路径并 normalize）。
     */
    public static boolean isWithin(Path base, Path target) {
        Path baseNorm = base.toAbsolutePath().normalize();
        Path targetNorm = target.toAbsolutePath().normalize();
        return targetNorm.startsWith(baseNorm);
    }

    /**
     * 检查目标是否位于 base 内，不在则抛异常。
     */
    public static void requireWithin(Path base, Path target) {
        if (!isWithin(base, target)) {
            throw new IllegalArgumentException("非法路径逃逸: " + target);
        }
    }
}
