package com.yangtze.bankwarning.ai.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.config.HybridRagProperties;
import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.context.AiCaller;
import com.yangtze.bankwarning.ai.service.KnowledgeService;
import com.yangtze.bankwarning.ai.service.SqlPermissionVectorSearch;
import com.yangtze.bankwarning.ai.store.JdbcRagTraceStore;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.rag.store.PgVectorStore;
import io.agentscope.core.rag.store.VDBStoreBase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 4 的「效果基线」离线 runner —— 手动跑，连真服务/真库，产出对照报告。
 *
 * <p><b>刻意不叫 {@code *Test}</b>：项目 pom 没有配置 surefire 分组，Surefire 默认只收集
 * {@code *Test.java / *Tests.java / Test*.java}，所以本类天然不会进 {@code mvn test}，
 * 不需要引入 {@code @Tag} 或改 pom。CI 门禁在 {@link RagRetrievalEvalTest} 里，二者必须分开：
 * 门禁是确定性的、零成本；本 runner 要 key、要网络、结果非确定。
 *
 * <p>运行（先 test-compile，再用 exec 插件或手工拼 test classpath）：
 * <pre>
 * mvn -pl bank-warning-ai -am test-compile
 * mvn -pl bank-warning-ai exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=com.yangtze.bankwarning.ai.rag.RagEvalCli \
 *     -Dexec.args="--self-check"
 * </pre>
 * 连真实服务：{@code --base-url http://localhost:8080 --token <JWT>}。
 * 连真库跑效果基线：{@code --jdbc}（key 取环境变量 DASHSCOPE_API_KEY，
 * 缺失时回落读 application-local.yml，也可用 {@code --key-file} 显式指定）。
 * <b>阶段 5</b>：再加 {@code --hybrid} 即打开混合检索，与不加该参数的 {@code --jdbc}
 * 形成 off / on 对照；http 模式的开关由服务端配置决定，命令行改不了。
 * <b>阶段 6</b>：加 {@code --sql-filter} 即让权限条件进向量 SQL（默认关闭，走 SDK 路径），
 * 管理员视角下 off / on 的每题结果必须完全一致——不一致就是实现有 bug。
 * 报告默认落到 {@code reports/rag-eval-<时间戳>.md}。
 */
public final class RagEvalCli {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 本机 key 文件（gitignore，只在开发机存在）：server 根视角与 ai 模块视角各试一次。 */
    private static final String DEFAULT_LOCAL_CONFIG =
            "bank-warning-app/src/main/resources/application-local.yml";
    private static final String DEFAULT_LOCAL_CONFIG_FROM_MODULE = "../" + DEFAULT_LOCAL_CONFIG;

    private RagEvalCli() {
    }

    public static void main(String[] args) {
        try {
            System.exit(execute(args));
        } catch (Exception e) {
            System.err.println("[rag-eval] 运行失败: " + e);
            System.exit(1);
        }
    }

    /** @return 0 成功，1 失败（供 main 转退出码；也让单测可以直接断言） */
    public static int execute(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        RagGoldenSet golden = opts.containsKey("golden")
                ? RagGoldenSet.fromJson(Files.readString(Path.of(opts.get("golden"))))
                : RagGoldenSet.load();

        int topK = Integer.parseInt(opts.getOrDefault("top-k", "5"));
        RetrievalSource source;
        if (opts.containsKey("jdbc")) {
            source = jdbc(opts);
        } else if (opts.containsKey("base-url")) {
            source = http(opts.get("base-url"), opts.getOrDefault("token", ""));
        } else {
            source = selfCheck();
        }

        RagEvalReport report = new RagRetrievalEvaluator(topK).run(golden, source.fn());
        // 补记运行元信息：没有这些字段，两次报告连「可复现」都做不到。
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("goldenVersion", golden.version);
        meta.put("retrievalSource", source.name());
        meta.put("topK", String.valueOf(topK));
        meta.put("caseCount", String.valueOf(golden.cases.size()));
        meta.put("note", "本报告的权威性上限 = 考卷标注的权威性；未经领域老师审定前只能自称『自拟题库』。");
        // RagEvalReport 的 meta 由 evaluator 生成，这里把附加信息拼进 markdown 头部。
        String body = report.toMarkdown();
        StringBuilder extra = new StringBuilder("\n## 本次运行补充\n\n");
        meta.forEach((k, v) -> extra.append("- ").append(k).append(": ").append(v).append('\n'));
        String markdown = body + extra;

        Path out = opts.containsKey("out")
                ? Path.of(opts.get("out"))
                : Path.of("reports", "rag-eval-" + LocalDateTime.now().format(TS) + ".md");
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, markdown, StandardCharsets.UTF_8);

        System.out.println("[rag-eval] 报告: " + out.toAbsolutePath());
        System.out.printf("[rag-eval] 计分题=%d, 平均Recall=%.4f, 平均Precision=%.4f, 拒答通过率=%.4f, 异常=%d%n",
                report.countedCases(), report.meanRecall(), report.meanPrecision(),
                report.refusalPassRate(), report.errorCount());
        return report.errorCount() > 0 ? 1 : 0;
    }

    /** 默认自检：不联网、不需要 key，只验证 runner 本身能跑通并产出报告。 */
    private static RetrievalSource selfCheck() {
        RagRetrievalEvaluator.RetrievalFn fn = (question, topK, caller, traceId) -> {
            // 拒答对抗题、以及尚未灌入冻结口径的 B 类题，都应返回空；
            // 其余题返回库内现存公共语料 BMS.docx。
            if (question.contains("3m") || question.contains("GB 50286")
                    || question.contains("Zd") || question.contains("Zb")
                    || question.contains("Dsed") || question.contains("权重")
                    || question.contains("风险等级 0") || question.contains("PL 和 LC")) {
                return List.of();
            }
            return List.of("BMS.docx");
        };
        return new RetrievalSource("self-check-fixture（非真实检索，仅用于 runner 自检）", fn);
    }

    /** 走线上唯一入口的 REST 形态：GET /v0/bank/ai/knowledge/search。 */
    private static RetrievalSource http(String baseUrl, String token) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        RagRetrievalEvaluator.RetrievalFn fn = (question, topK, caller, traceId) -> {
            String url = baseUrl.trim().replaceAll("/+$", "")
                    + "/v0/bank/ai/knowledge/search?query="
                    + URLEncoder.encode(question, StandardCharsets.UTF_8)
                    + "&topK=" + topK;
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET();
            if (!token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> resp = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                // 不吞：503 代表知识库不可用，必须变成报告里的 ERROR，而不是「没查到」。
                throw new IOException("检索接口 HTTP " + resp.statusCode() + ": " + resp.body());
            }
            List<Map<String, Object>> rows = JSON.readValue(resp.body(),
                    new TypeReference<List<Map<String, Object>>>() {});
            List<String> ids = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object docId = row.get("docId");
                if (docId != null) {
                    ids.add(String.valueOf(docId));
                }
            }
            return ids;
        };
        return new RetrievalSource("http " + baseUrl, fn);
    }

    /**
     * 直连真实 PostgreSQL + 真实 DashScope embedding，跑出「改造前」基线。
     *
     * <p>只读为主：traceEnabled=false 不写 {@code ai_rag_traces}；
     * {@code PgVectorStore.builder()} 只会执行 IF NOT EXISTS 的幂等 DDL。
     * 密钥从环境变量读，不落命令行、不写报告。
     */
    private static RetrievalSource jdbc(Map<String, String> opts) throws Exception {
        String key = resolveDashScopeKey(opts);
        String url = opts.getOrDefault("jdbc-url",
                "jdbc:postgresql://localhost:5432/bank_risk_db");
        String user = opts.getOrDefault("db-user", "postgres");
        String password = opts.getOrDefault("db-password", "123456");

        VDBStoreBase store = PgVectorStore.builder()
                .jdbcUrl(url)
                .username(user)
                .password(password)
                .schema("public")
                .tableName("ai_knowledge_store")
                .dimensions(1024)
                .distanceType(PgVectorStore.DistanceType.COSINE)
                .build();
        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, password);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(ds);
        // 与线上默认一致，但关掉留痕：评测不该往生产表里写观测数据。
        RagProperties props = new RagProperties(
                true, true, true, false, 5, 5, 5, 0.4, 5, 15_000L, 600_000L);
        // 阶段 5：--hybrid 打开第二路；不加该参数即「关闭态」，与 4A 基线口径一致。
        HybridRagProperties hybrid = opts.containsKey("hybrid")
                ? HybridRagProperties.enabledWithDefaults()
                : HybridRagProperties.disabled();
        KnowledgeService service = new KnowledgeService(
                DashScopeTextEmbedding.builder()
                        .apiKey(key)
                        .modelName("text-embedding-v4")
                        .dimensions(1024)
                        .build(),
                store, jdbcTemplate, new JdbcRagTraceStore(jdbcTemplate, props), props, hybrid,
                // 阶段 6：--sql-filter 打开「权限进 SQL」的新路径，不加则该路径关闭，
                // 与 4A 基线口径逐字一致。两者都跑一遍即可做 off/on 对照。
                opts.containsKey("sql-filter")
                        ? new SqlPermissionVectorSearch(jdbcTemplate, true)
                        : SqlPermissionVectorSearch.disabled());

        // 真库路径覆盖 fetchScored()：把 Document.getScore() 一并带进报告，
        // 这样「该拒答的题」才能看到它到底拿了多高的相似度，而不是只有 0/1 的空/非空。
        RagRetrievalEvaluator.RetrievalFn fn = new RagRetrievalEvaluator.RetrievalFn() {
            @Override
            public List<String> fetch(String question, int topK, String caller, String traceId) {
                return fetchScored(question, topK, caller, traceId).stream()
                        .map(RagRetrievalEvaluator.RetrievalFn.Scored::docId)
                        .toList();
            }

            @Override
            public List<RagRetrievalEvaluator.RetrievalFn.Scored> fetchScored(
                    String question, int topK, String caller, String traceId) {
                AiCaller who = switch (caller) {
                    case "admin" -> AiCaller.forAdmin();
                    case "anonymous" -> AiCaller.anonymous();
                    // 考卷里的 user 是普通用户视角；BMS.docx 已被 V16 标为公共，应仍可见。
                    default -> AiCaller.forUser(7L);
                };
                return service.retrieve(question, KnowledgeService.Scenario.SEARCH,
                                who, topK, traceId)
                        .stream()
                        .map(d -> new RagRetrievalEvaluator.RetrievalFn.Scored(
                                String.valueOf(d.getMetadata().getDocId()), d.getScore()))
                        .toList();
            }
        };
        return new RetrievalSource(
                "jdbc 真库直连 + text-embedding-v4（trace 关闭，未写 ai_rag_traces，hybrid="
                        + (hybrid.enabled ? "on" : "off") + "）", fn);
    }

    /**
     * DashScope key 的两条来源：环境变量优先，否则读 {@code application-local.yml} 的
     * {@code agentscope.dashscope.api-key}（该文件被 gitignore，只在本机存在）。
     *
     * <p>为什么需要回落：项目实际把本地 key 放在 application-local.yml，而 4A 首次基线
     * 只认环境变量，换一个 shell 就跑不了，破坏了「重跑结果是否一致」这一评测前提。
     * key 绝不写入报告、命令行参数或日志；{@code ${...}} 占位符不算有效值。
     */
    private static String resolveDashScopeKey(Map<String, String> opts) throws IOException {
        String fromEnv = System.getenv("DASHSCOPE_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        List<String> candidates = opts.containsKey("key-file")
                ? List.of(opts.get("key-file"))
                : List.of(DEFAULT_LOCAL_CONFIG, DEFAULT_LOCAL_CONFIG_FROM_MODULE);
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            String section = "";
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int indent = line.length() - line.stripLeading().length();
                if (indent <= 2 && trimmed.endsWith(":")) {
                    section = trimmed.substring(0, trimmed.length() - 1).trim();
                    continue;
                }
                if ("dashscope".equals(section) && trimmed.startsWith("api-key")) {
                    int colon = trimmed.indexOf(':');
                    if (colon < 0) {
                        continue;
                    }
                    String value = trimmed.substring(colon + 1).trim()
                            .replaceAll("^['\"]|['\"]$", "");
                    if (!value.isBlank() && !value.startsWith("${")) {
                        return value;
                    }
                }
            }
        }
        throw new IllegalStateException(
                "jdbc 模式需要 DashScope key：设置环境变量 DASHSCOPE_API_KEY，或用 --key-file "
                        + "指向 application-local.yml（默认查找 " + DEFAULT_LOCAL_CONFIG + "）");
    }

    private record RetrievalSource(String name, RagRetrievalEvaluator.RetrievalFn fn) {
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                continue;
            }
            if ("--self-check".equals(a)) {
                opts.put("self-check", "true");
                continue;
            }
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                opts.put(a.substring(2), args[++i]);
            } else {
                opts.put(a.substring(2), "true");
            }
        }
        return opts;
    }
}
