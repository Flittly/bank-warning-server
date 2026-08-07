package com.yangtze.bankwarning.ai.config;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.client.ai.NacosAiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.security.SkillContentVerifier;
import com.yangtze.bankwarning.ai.security.SkillPathGuard;
import io.agentscope.core.nacos.skill.NacosSkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 持有 NacosSkillRepository 实例，封装 Nacos 连接生命周期。
 * 仅当 agentscope.nacos.enabled=true 时创建。
 * 连接失败时 isAvailable() 返回 false，调用方应降级到本地 Skill。
 */
@Component
@ConditionalOnProperty(prefix = "agentscope.nacos", name = "enabled", havingValue = "true")
public class NacosSkillRepositoryHolder {

    private static final Logger log = LoggerFactory.getLogger(NacosSkillRepositoryHolder.class);

    private final NacosSkillRepository repository;
    private final boolean available;
    private final String serverAddr;
    private final String namespace;
    private final String username;
    private final String password;
    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> tokenCache = new AtomicReference<>();
    private volatile long tokenExpiresAt = 0L;
    private final java.util.concurrent.ConcurrentHashMap<String, String> cachedSkillNames = new java.util.concurrent.ConcurrentHashMap<>();
    private com.alibaba.nacos.api.ai.AiService aiService;
    private final SkillContentVerifier contentVerifier;

    public NacosSkillRepositoryHolder(
            @Value("${agentscope.nacos.server-addr:127.0.0.1:8848}") String serverAddr,
            @Value("${agentscope.nacos.namespace:public}") String namespace,
            @Value("${agentscope.nacos.username:}") String username,
            @Value("${agentscope.nacos.password:}") String password,
            SkillContentVerifier contentVerifier) {
        this.serverAddr = serverAddr;
        this.namespace = namespace;
        this.username = username;
        this.password = password;
        this.contentVerifier = contentVerifier;
        NacosSkillRepository repo = null;
        boolean ok = false;
        try {
            Properties props = new Properties();
            props.put(PropertyKeyConst.SERVER_ADDR, serverAddr);
            props.put(PropertyKeyConst.NAMESPACE, namespace);
            if (!username.isBlank()) {
                props.put(PropertyKeyConst.USERNAME, username);
            }
            if (!password.isBlank()) {
                props.put(PropertyKeyConst.PASSWORD, password);
            }
            AiService aiService = new NacosAiService(props);
            repo = new NacosSkillRepository(aiService, namespace);
            ok = true;
            this.aiService = aiService;
            log.info("[NacosSkill] connected to {} namespace={}", serverAddr, namespace);
            tryWarmupCache();
        } catch (NacosException | RuntimeException e) {
            log.warn("[NacosSkill] failed to connect to Nacos, skills will fall back to local: {}", e.getMessage());
            this.aiService = null;
        }
        this.repository = repo;
        this.available = ok;
    }

    public NacosSkillRepository getRepository() {
        return repository;
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * 通过 Nacos HTTP API 获取所有 skill 名称列表。
     * NacosSkillRepository.getAllSkillNames() 不支持列表，所以直接调 HTTP。
     */
    public List<String> listNacosSkillNames() {
        return new ArrayList<>(cachedSkillNames.keySet());
    }

    public String getNacosSkillDesc(String name) {
        return cachedSkillNames.getOrDefault(name, "");
    }

    public void trackSkill(String name) {
        cachedSkillNames.put(name, "");
    }

    public void untrackSkill(String name) {
        cachedSkillNames.remove(name);
    }

    public void downloadSkillToLocal(String skillName) throws Exception {
        if (aiService == null) throw new IllegalStateException("Nacos AI service not available");
        byte[] zip;
        // 优先用 SDK，SDK 内部自动处理 auth 和路径
        try {
            zip = aiService.downloadSkillZip(skillName);
        } catch (Exception sdkEx) {
            // SDK 失败则降级用 Admin API + accessToken
            log.warn("[NacosSkill] SDK download failed, fallback to Admin API: {}", sdkEx.getMessage());
            zip = downloadViaAdmin(skillName);
        }
        if (zip == null || zip.length == 0) throw new RuntimeException("下载到空内容");
        // 下载后先做完整性校验（sha256 清单 + HMAC 签名），通过才允许落盘，拒绝被篡改的 skill
        if (!contentVerifier.verifyZip(zip)) {
            throw new RuntimeException("Skill 内容完整性校验失败，拒绝落盘: " + skillName);
        }
        // 校验通过后再解析并剥离公共根目录，得到归一化的文件集合
        SkillContentVerifier.ZipContent content = contentVerifier.parseZip(zip);
        Path skillsDir = Paths.get("src/main/resources/skills/" + skillName).toAbsolutePath().normalize();
        Files.createDirectories(skillsDir);
        for (Map.Entry<String, byte[]> entry : content.files.entrySet()) {
            // 落盘前同样走路径防逃逸，防止 zip 内 ../ 条目覆盖外部文件
            Path outFile = SkillPathGuard.safeResolve(skillsDir, entry.getKey());
            Files.createDirectories(outFile.getParent());
            Files.write(outFile, entry.getValue());
        }
        log.info("[NacosSkill] downloaded {} to {}", skillName, skillsDir);
    }

    private byte[] downloadViaAdmin(String skillName) throws Exception {
        String token = ensureToken();
        HttpHeaders headers = new HttpHeaders();
        headers.set("accessToken", token);
        // 尝试列表拿版本号
        String listUrl = "http://" + serverAddr + "/nacos/v3/admin/ai/skills/list?pageNo=1&pageSize=1"
                + "&namespaceId=" + namespace
                + "&skillName=" + java.net.URLEncoder.encode(skillName, "UTF-8") + "&search=accurate";
        ResponseEntity<String> listResp = http.exchange(listUrl, org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        JsonNode items = json.readTree(listResp.getBody()).path("data").path("pageItems");
        String version = (items.isArray() && !items.isEmpty())
                ? items.get(0).path("version").asText("1.0.0") : "1.0.0";
        HttpEntity<Void> req = new HttpEntity<>(headers);
        // 控制台 API 路径
        String dlUrl = "http://" + serverAddr + "/nacos/v1/console/ai/skills/download"
                + "?namespaceId=" + namespace
                + "&skillName=" + java.net.URLEncoder.encode(skillName, "UTF-8")
                + "&version=" + java.net.URLEncoder.encode(version, "UTF-8");
        ResponseEntity<byte[]> dlResp = http.exchange(dlUrl, org.springframework.http.HttpMethod.GET,
                req, byte[].class);
        if (dlResp.getBody() != null) return dlResp.getBody();
        throw new RuntimeException("控制台下载无响应");
    }

    private void tryWarmupCache() {
        try {
            String token = ensureToken();
            String url = "http://" + serverAddr + "/nacos/v3/admin/ai/skills/list?pageNo=1&pageSize=100&namespaceId="
                    + namespace + "&search=blur";
            HttpHeaders headers = new HttpHeaders();
            headers.set("accessToken", token);
            HttpEntity<Void> req = new HttpEntity<>(headers);
            ResponseEntity<String> resp = http.exchange(url, org.springframework.http.HttpMethod.GET, req, String.class);
            JsonNode root = json.readTree(resp.getBody());
            JsonNode items = root.path("data").path("pageItems");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String name = item.path("name").asText("");
                    if (!name.isBlank()) {
                        cachedSkillNames.put(name, item.path("description").asText(""));
                        log.info("[NacosSkill] warmup: {}", name);
                    }
                }
            }
            log.info("[NacosSkill] warmup complete, {} skills cached", cachedSkillNames.size());
        } catch (Exception e) {
            log.info("[NacosSkill] cache warmup not available: {}", e.getMessage());
        }
    }

    /**
     * 上传 skill 到 Nacos（绕过 NacosSkillRepository.save() 这个 no-op，
     * 直接调 Nacos admin HTTP API：POST /nacos/v3/admin/ai/skills/upload）。
     *
     * @param name         skill 名（仅日志用，Nacos 实际从 ZIP 内部 SKILL.md frontmatter 读）
     * @param content      SKILL.md 内容（作为 ZIP 根的 SKILL.md）
     * @param resources    其他文件：key=相对路径（如 scripts/extract.py），value=内容
     *                     （如果是 base64: 前缀则按 base64 解码后写入）
     * @return Nacos 返回的 skill 名称
     */
    public String uploadSkill(String name, String content, Map<String, String> resources) {
        try {
            byte[] zip = buildSkillZip(content, resources);
            String token = ensureToken();
            String url = "http://" + serverAddr + "/nacos/v3/admin/ai/skills/upload?namespaceId=" + namespace;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("accessToken", token);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            ByteArrayResource resource = new ByteArrayResource(zip) {
                @Override
                public String getFilename() {
                    return "skill.zip";
                }
            };
            body.add("file", resource);

            HttpEntity<MultiValueMap<String, Object>> req = new HttpEntity<>(body, headers);
            ResponseEntity<String> resp = http.postForEntity(url, req, String.class);
            String respBody = resp.getBody();
            log.info("[NacosSkill] upload {} status={} body={}", name, resp.getStatusCode(), respBody);
            trackSkill(name);

            JsonNode root = json.readTree(respBody);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                throw new RuntimeException("Nacos upload failed: " + respBody);
            }
            return root.path("data").asText(name);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload skill to Nacos: " + e.getMessage(), e);
        }
    }

    private byte[] buildSkillZip(String skillContent, Map<String, String> resources) throws IOException {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        // 1. SKILL.md 作为根文件
        if (skillContent != null) {
            contents.put("SKILL.md", skillContent.getBytes(StandardCharsets.UTF_8));
        }
        // 2. 其他 resources（避开 SKILL.md 避免重复）
        if (resources != null) {
            for (Map.Entry<String, String> entry : resources.entrySet()) {
                String path = entry.getKey();
                if (path == null) continue;
                String normalized = path.replace('\\', '/');
                if (normalized.equals("SKILL.md") || normalized.equals("./SKILL.md")) continue;
                String value = entry.getValue();
                if (value == null) continue;
                byte[] data;
                if (value.startsWith("base64:")) {
                    // 二进制资源（图片、字体等）以 base64 传递，这里解码回原始字节
                    data = Base64.getDecoder().decode(value.substring("base64:".length()));
                } else {
                    data = value.getBytes(StandardCharsets.UTF_8);
                }
                contents.put(normalized, data);
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
                ZipEntry ze = new ZipEntry(entry.getKey());
                zos.putNextEntry(ze);
                zos.write(entry.getValue());
                zos.closeEntry();
            }
            // 最后写入内嵌校验清单（sha256 + HMAC 签名），消费方下载后按此校验
            contentVerifier.writeChecksumEntry(zos, contents);
        }
        return baos.toByteArray();
    }

    private String ensureToken() {
        long now = System.currentTimeMillis();
        String cached = tokenCache.get();
        if (cached != null && now < tokenExpiresAt - 60_000) {
            return cached;
        }
        try {
            String url = "http://" + serverAddr + "/nacos/v1/auth/users/login";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            String form = "username=" + username + "&password=" + password;
            HttpEntity<String> req = new HttpEntity<>(form, headers);
            ResponseEntity<String> resp = http.postForEntity(url, req, String.class);
            JsonNode root = json.readTree(resp.getBody());
            String token = root.path("accessToken").asText();
            long ttl = root.path("tokenTtl").asLong(18000) * 1000L;
            tokenCache.set(token);
            tokenExpiresAt = now + ttl;
            log.info("[NacosSkill] obtained new accessToken, ttl={}s", ttl / 1000);
            return token;
        } catch (Exception e) {
            throw new RuntimeException("Failed to login Nacos: " + e.getMessage(), e);
        }
    }
}
