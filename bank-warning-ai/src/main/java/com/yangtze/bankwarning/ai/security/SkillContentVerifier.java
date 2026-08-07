package com.yangtze.bankwarning.ai.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Skill 内容完整性校验器。
 *
 * 上传侧：对 zip 内每个文件计算 sha256，生成 .bank-checksum.sha256 清单内嵌 zip，
 *         清单内容用 HMAC-SHA256 签名（密钥来自配置，与 Nacos 无关）。
 * 下载侧：先验清单 HMAC 签名，再逐文件比对 sha256，任一失败即拒绝落盘。
 *
 * zip 可能带一层公共根目录（如 Nacos 以 skill 名包裹），parseZip 统一剥离。
 */
@Component
public class SkillContentVerifier {

    private static final Logger log = LoggerFactory.getLogger(SkillContentVerifier.class);

    public static final String CHECKSUM_ENTRY = ".bank-checksum.sha256";

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String SHA256 = "SHA-256";

    private final byte[] hmacKey;

    public SkillContentVerifier(@Value("${app.ai.skill.verify.hmac-secret:}") String hmacSecret) {
        this.hmacKey = hmacSecret == null || hmacSecret.isBlank() ? new byte[0] : hmacSecret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isSigningEnabled() {
        return hmacKey.length > 0;
    }

    /**
     * 将校验清单写入 zip 输出流。必须在所有业务 entry 写入后调用。
     * 当 hmacKey 未配置时仅写 sha256（无签名），校验时也只验哈希。
     */
    public void writeChecksumEntry(ZipOutputStream zos, Map<String, byte[]> fileContents) throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : fileContents.entrySet()) {
            hashes.put(e.getKey(), sha256Hex(e.getValue()));
        }
        StringBuilder manifest = new StringBuilder();
        for (Map.Entry<String, String> e : hashes.entrySet()) {
            manifest.append(e.getValue()).append("  ").append(e.getKey()).append('\n');
        }
        String body = manifest.toString();
        String content = isSigningEnabled()
                ? body + "signature=" + hmacHex(body)
                : body;
        ZipEntry ze = new ZipEntry(CHECKSUM_ENTRY);
        zos.putNextEntry(ze);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /**
     * 解析 zip：剥离公共根目录，返回按归一化路径组织的文件内容。
     */
    public ZipContent parseZip(byte[] zip) {
        ZipContent content = new ZipContent();
        if (zip == null || zip.length == 0) return content;
        Map<String, byte[]> raw = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String rawName = entry.getName().replace('\\', '/');
                byte[] data = zis.readAllBytes();
                if (rawName.equals(CHECKSUM_ENTRY) || rawName.endsWith("/" + CHECKSUM_ENTRY)) {
                    content.checksumContent = data;
                    // 清单所在位置隐含了公共根目录前缀（如 Nacos 以 skill 名包裹），
                    // 从清单条目路径中提取该前缀，后续对所有业务条目统一剥离
                    content.rootPrefix = rawName.substring(0, rawName.length() - CHECKSUM_ENTRY.length());
                } else {
                    raw.put(rawName, data);
                }
            }
        } catch (IOException e) {
            log.error("[skill-verify] 解析 zip 失败: {}", e.getMessage());
            return content;
        }
        for (Map.Entry<String, byte[]> e : raw.entrySet()) {
            String normalized = stripRoot(e.getKey(), content.rootPrefix);
            if (normalized != null && !normalized.isBlank()) {
                content.files.put(normalized, e.getValue());
            }
        }
        return content;
    }

    /**
     * 校验原始 zip 字节（下载后、解压前）。
     *
     * @return true=通过或无清单（本地/无签名 zip）；false=校验失败，应拒绝落盘
     */
    public boolean verifyZip(byte[] zip) {
        ZipContent content = parseZip(zip);
        if (content.checksumContent == null) {
            log.warn("[skill-verify] zip 无校验清单，跳过");
            return true;
        }
        try {
            // 在临时目录重建 zip 内容后按 verifyDir 走同一套校验逻辑，
            // 复用清单解析/哈希比对代码，避免两套实现漂移
            Path tmp = Files.createTempDirectory("skill-verify-");
            for (Map.Entry<String, byte[]> e : content.files.entrySet()) {
                Path target = SkillPathGuard.safeResolve(tmp, e.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, e.getValue());
            }
            Files.write(tmp.resolve(CHECKSUM_ENTRY), content.checksumContent);
            boolean ok = verifyDir(tmp);
            // 校验结束后清理临时目录（从最深路径开始删）
            try (var stream = Files.walk(tmp)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
            return ok;
        } catch (Exception e) {
            log.error("[skill-verify] zip 校验异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 校验解压后的 skill 目录。
     *
     * @param skillDir 已解压到磁盘的 skill 目录
     * @return 校验是否通过
     */
    public boolean verifyDir(Path skillDir) {
        Path checksumFile = skillDir.resolve(CHECKSUM_ENTRY);
        if (!Files.exists(checksumFile)) {
            log.warn("[skill-verify] {} 缺失，无法校验（本地/无签名 skill 跳过）", skillDir);
            return true;
        }
        try {
            List<String> lines = Files.readAllLines(checksumFile, StandardCharsets.UTF_8);
            StringBuilder body = new StringBuilder();
            Map<String, String> expected = new LinkedHashMap<>();
            boolean signed = false;
            for (String line : lines) {
                if (line.startsWith("signature=")) {
                    signed = true;
                    String sig = line.substring("signature=".length()).trim();
                    // 只有配置了密钥（isSigningEnabled）才要求签名，且必须 constant-time 比较防时序侧信道
                    if (isSigningEnabled() && !constantTimeEquals(hmacHex(body.toString()), sig)) {
                        log.error("[skill-verify] {} HMAC 签名不匹配，可能被篡改", skillDir);
                        return false;
                    }
                } else if (!line.isBlank()) {
                    // body 累积除 signature 外的所有哈希行，作为 HMAC 的输入原文
                    body.append(line).append('\n');
                    String[] parts = line.split("\\s{2,}", 2);
                    if (parts.length == 2) {
                        expected.put(parts[1].trim(), parts[0].trim());
                    }
                }
            }
            // 配置了密钥但清单没签名，说明发布方未按约定签名，视为不可信
            if (isSigningEnabled() && !signed) {
                log.error("[skill-verify] {} 配置要求签名但清单未签名", skillDir);
                return false;
            }
            if (expected.isEmpty()) {
                log.warn("[skill-verify] {} 清单为空", skillDir);
                return true;
            }
            // 逐文件比对（跳过清单自身）
            for (Map.Entry<String, String> e : expected.entrySet()) {
                Path f = skillDir.resolve(e.getKey()).normalize();
                if (!SkillPathGuard.isWithin(skillDir, f)) {
                    log.error("[skill-verify] {} 清单条目路径逃逸: {}", skillDir, e.getKey());
                    return false;
                }
                if (!Files.exists(f)) {
                    log.error("[skill-verify] {} 清单条目缺失: {}", skillDir, e.getKey());
                    return false;
                }
                byte[] content = Files.readAllBytes(f);
                if (!sha256Hex(content).equals(e.getValue().toLowerCase(Locale.ROOT))) {
                    log.error("[skill-verify] {} 文件哈希不匹配: {}", skillDir, e.getKey());
                    return false;
                }
            }
            log.info("[skill-verify] {} 校验通过 ({} files)", skillDir, expected.size());
            return true;
        } catch (IOException e) {
            log.error("[skill-verify] 校验失败 {}: {}", skillDir, e.getMessage());
            return false;
        }
    }

    /** 剥离公共根目录前缀 */
    public static String stripRoot(String name, String rootPrefix) {
        if (rootPrefix == null || rootPrefix.isEmpty()) return name;
        if (name.startsWith(rootPrefix)) return name.substring(rootPrefix.length());
        return name;
    }

    /** zip 解析结果 */
    public static final class ZipContent {
        public final Map<String, byte[]> files = new LinkedHashMap<>();
        public byte[] checksumContent;
        public String rootPrefix = "";
    }

    /** 生成指纹 */
    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(SHA256);
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 生成防伪章 */
    private String hmacHex(String data) {
        if (!isSigningEnabled()) return "";
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(hmacKey, HMAC_ALGO));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    /** 安全比对 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
