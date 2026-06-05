package com.yangtze.bankwarning.ai.config;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.client.ai.NacosAiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Base64;
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

    public NacosSkillRepositoryHolder(
            @Value("${agentscope.nacos.server-addr:127.0.0.1:8848}") String serverAddr,
            @Value("${agentscope.nacos.namespace:public}") String namespace,
            @Value("${agentscope.nacos.username:}") String username,
            @Value("${agentscope.nacos.password:}") String password) {
        this.serverAddr = serverAddr;
        this.namespace = namespace;
        this.username = username;
        this.password = password;
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
            log.info("[NacosSkill] connected to {} namespace={}", serverAddr, namespace);
        } catch (NacosException | RuntimeException e) {
            log.warn("[NacosSkill] failed to connect to Nacos, skills will fall back to local: {}", e.getMessage());
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // 1. SKILL.md 作为根文件
            if (skillContent != null) {
                ZipEntry e = new ZipEntry("SKILL.md");
                zos.putNextEntry(e);
                zos.write(skillContent.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
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
                    ZipEntry ze = new ZipEntry(normalized);
                    zos.putNextEntry(ze);
                    if (value.startsWith("base64:")) {
                        zos.write(Base64.getDecoder().decode(value.substring("base64:".length())));
                    } else {
                        zos.write(value.getBytes(StandardCharsets.UTF_8));
                    }
                    zos.closeEntry();
                }
            }
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
