package com.yangtze.bankwarning.ai.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 阶段 4 考卷：加载 + 结构校验。
 *
 * <p>考卷放在 {@code src/test/resources/rag/golden-set.json}，随 git 带走；
 * 它是评测的「刻度」，不是产出的结论。所有 {@code scored=false} 的题只登记不计分，
 * 避免库里期望块不存在时 Recall 恒 0、把基线退化成一堆没有区分度的零。
 */
public final class RagGoldenSet {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class GoldenCase {
        public String id;
        public String category;
        public String question;
        public List<String> expectedDocIds = new ArrayList<>();
        public boolean mustRefuse;
        public String caller = "admin";
        public boolean scored = true;
        public String note = "";

        public List<String> expectedDocIdsOrEmpty() {
            return expectedDocIds == null ? List.of() : expectedDocIds;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Root {
        public String version = "1";
        public String note = "";
        public List<GoldenCase> cases = new ArrayList<>();
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CALLERS = Set.of("admin", "user", "anonymous");
    private static final Set<String> CATEGORIES = Set.of("A", "B", "C");

    public final String version;
    public final String note;
    public final List<GoldenCase> cases;

    private RagGoldenSet(Root root) {
        this.version = root.version == null ? "1" : root.version;
        this.note = root.note == null ? "" : root.note;
        this.cases = root.cases == null ? List.of() : List.copyOf(root.cases);
    }

    public static RagGoldenSet load() throws IOException {
        return fromClasspath("rag/golden-set.json");
    }

    public static RagGoldenSet fromClasspath(String resource) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("找不到考卷资源: " + resource);
            }
            return new RagGoldenSet(JSON.readValue(in, Root.class));
        }
    }

    public static RagGoldenSet fromJson(String json) throws IOException {
        return new RagGoldenSet(JSON.readValue(json, Root.class));
    }

    /** 供单元测试内联构造小考卷；内联 JSON 由编译器保证格式，失败即测试错误。 */
    public static RagGoldenSet fromJsonForTest(String json) {
        try {
            return fromJson(json);
        } catch (IOException e) {
            throw new IllegalStateException("内联考卷解析失败", e);
        }
    }

    /** 标注缺失时直接失败，绝不悄悄把一道没标注的题算进指标。 */
    public void validate() {
        if (cases.isEmpty()) {
            throw new IllegalStateException("考卷为空：至少要有 1 道题");
        }
        Set<String> ids = new HashSet<>();
        for (GoldenCase c : cases) {
            if (isBlank(c.id) || isBlank(c.question)) {
                throw new IllegalStateException("考卷存在缺 id 或 question 的题");
            }
            if (!ids.add(c.id)) {
                throw new IllegalStateException("考卷 id 重复: " + c.id);
            }
            if (!CATEGORIES.contains(c.category)) {
                throw new IllegalStateException("考卷 category 非法: " + c.id + " / " + c.category);
            }
            String caller = isBlank(c.caller) ? "admin" : c.caller;
            if (!CALLERS.contains(caller)) {
                throw new IllegalStateException("考卷 caller 非法: " + c.id + " / " + c.caller);
            }
            boolean hasExpected = !c.expectedDocIdsOrEmpty().isEmpty();
            if (c.scored && !hasExpected && !c.mustRefuse) {
                throw new IllegalStateException("计分题既无期望块也非拒答题: " + c.id);
            }
            if (c.mustRefuse && hasExpected) {
                throw new IllegalStateException("拒答题不应同时配置期望块: " + c.id);
            }
        }
    }

    public long scoredCount() {
        return cases.stream().filter(c -> c.scored).count();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
