package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.context.AiCaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 任务上下文：把「任务」翻译成模型能用的东西。
 *
 * <p>存在的理由：智能体手里所有工具（{@code query_risk_data}、{@code generate_risk_distribution_map}…）
 * 都要 {@code task_id}，却没有任何一件能把用户说的「那个江苏段的任务」换成 id ——
 * 模型拿不到 id 就直接编一个，SQL 命中 0 行，最后回一句「未查询到任务ID为 XXX 的数据」。
 *
 * <p>两条入口都收在这里，SQL 与文案只有一份：
 * <ul>
 *   <li>{@link #describeForPrompt(List, AiCaller)} —— 用户把任务拖进对话时，前端发 {@code taskIds}，
 *       这里查成「名称 + id + 状态」拼进 prompt，模型不必再问；</li>
 *   <li>{@link #search(String, Integer, AiCaller)} —— 模型自己按名字检索（{@code search_tasks} 工具）。</li>
 * </ul>
 *
 * <p>口径：{@code tasks.deleted_at IS NULL}，并按调用方身份过滤。
 *
 * <h2>为什么每个入口都必须显式收 {@link AiCaller}</h2>
 * 这个服务的两个入口跑在**不同线程**上：{@code describeForPrompt} 在 Tomcat 请求线程
 * （SecurityContext 可用），{@code search} 在模型 ReAct 循环的线程（ThreadLocal 为空）。
 * 早先这里直接读 {@code SecurityUtils.getCurrentUserIdForDataFilter()}，于是工具那一路
 * 读到 null，而 null 被解释成「管理员」⇒ 普通用户能搜出别人的任务名和 task_id。
 * 现在身份由调用方从 RuntimeContext 注入传递，本类不再自行读 ThreadLocal。
 *
 * <p>任务名**没有唯一约束**（主键是 task_id），所以名字命中多条是常态，必须如实列出来让模型/用户确认。
 */
public class TaskContextService {

    private static final Logger log = LoggerFactory.getLogger(TaskContextService.class);

    /** 一次对话最多认领多少个任务：拖入 20 个以上基本是误操作，prompt 也会被清单撑爆。 */
    static final int MAX_TASKS = 20;
    private static final int DEFAULT_SEARCH_LIMIT = 10;
    private static final int MAX_SEARCH_LIMIT = 30;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbcTemplate;

    public TaskContextService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------ 拖入的任务

    /**
     * 把前端拖入的 taskIds 变成一段可以直接拼进 prompt 的文本。
     *
     * @param caller 发起本次对话的身份；只查得到他自己（或管理员看全部）的任务
     * @return 无有效 id 时返回空串（调用方据此决定要不要加这一段）
     */
    public String describeForPrompt(List<String> rawIds, AiCaller caller) {
        AiCaller who = AiCaller.orAnonymous(caller);
        List<String> ids = normalizeIds(rawIds);
        if (ids.isEmpty()) {
            return "";
        }
        if (!who.isAuthenticated()) {
            // 未登录就别声称「任务不存在」——那是在拿身份失败当业务结论
            return "【任务上下文不可用】" + AiCaller.ANONYMOUS_MESSAGE + "\n";
        }
        Map<String, Map<String, Object>> found;
        try {
            found = loadByIds(ids, who);
        } catch (RuntimeException e) {
            // 查不动也不能把整轮对话打死：降级成「只给 id」，模型照样能用 id 调工具。
            // 注意别复用 renderLocked —— 那会把「查不到」说成「任务已删除」，骗了模型。
            log.warn("[task-context] 查询拖入任务失败（降级为只给 id）: {}", e.getMessage());
            return renderIdsOnly(ids);
        }
        return renderLocked(ids, found);
    }

    /**
     * 只保留「像 task_id」的值：去空白、去重、挡掉会把 prompt 结构搞坏的字符（空格/引号/换行）。
     *
     * <p>这一段不是洁癖：id 会被原样拼进给模型的文本里，混进换行就等于让调用方能往 prompt
     * 里插一行指令。
     */
    static List<String> normalizeIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object item : raw) {
            if (item == null) {
                continue;
            }
            String id = String.valueOf(item).strip();
            if (id.isEmpty() || id.length() > 64) {
                continue;
            }
            if (!id.matches("[A-Za-z0-9_.:-]+")) {
                continue;
            }
            out.add(id);
            if (out.size() >= MAX_TASKS) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    private Map<String, Map<String, Object>> loadByIds(List<String> ids, AiCaller caller) {
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        StringBuilder sql = new StringBuilder("SELECT t.task_id, t.task_name, t.status, t.created_at, ")
                .append(SECTION_COUNT)
                .append(" FROM tasks t WHERE t.task_id IN (").append(placeholders).append(')')
                .append(" AND t.deleted_at IS NULL");
        List<Object> args = new ArrayList<>(ids);
        appendUserFilterIfNeeded(sql, args, caller);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byId.put(String.valueOf(row.get("task_id")), row);
        }
        return byId;
    }

    /** 锁定任务 block：按拖入顺序输出，附带「不许索要/编造 id」的硬规则。 */
    static String renderLocked(List<String> ids, Map<String, Map<String, Object>> found) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本次对话已锁定的任务】用户从界面把下列任务拖进了对话，task_id 是权威标识：\n");
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> row = found.get(id);
            if (row == null) {
                missing.add(id);
                continue;
            }
            sb.append("- ").append(taskLine(row)).append('\n');
        }
        appendRules(sb);
        if (!missing.isEmpty()) {
            sb.append("注意：以下 task_id 在库中不存在（可能已被删除）：")
                    .append(String.join("、", missing))
                    .append(" —— 用户问及时请先说明这一点。\n");
        }
        return sb.toString();
    }

    /** 降级版：库里查不到详情（DB 抖动）时只交出 id —— 不得谎称任务已被删除。 */
    static String renderIdsOnly(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本次对话已锁定的任务】用户从界面把下列任务拖进了对话（详情暂时查不到，但 task_id 可用）：\n");
        for (String id : ids) {
            sb.append("- task_id: ").append(id).append('\n');
        }
        appendRules(sb);
        sb.append("说明：任务名称本次未能读到（数据库查询失败），不要因此判断任务不存在。\n");
        return sb.toString();
    }

    /** 三条硬规则：不索要、不编造、查不到就原样回报。两个版本共用，避免口径漂移。 */
    private static void appendRules(StringBuilder sb) {
        sb.append("规则：\n")
                .append("1. 涉及这些任务的分析、取数、出图、写报告，直接使用上面给出的 task_id 调用工具，")
                .append("不要向用户索要 task_id；\n")
                .append("2. 绝不编造、截断或改写 task_id（编一个查不到，结果就是一段假的「未查询到」）；\n")
                .append("3. 工具若返回「未查询到」，把这条 task_id 原样告诉用户并请他确认任务是否已删除，")
                .append("不要换个 id 再试。\n");
    }

    // ------------------------------------------------------------------ 按名字检索

    /**
     * {@code search_tasks} 的实现：按关键词模糊匹配任务名 / task_id；空关键词 = 最近任务。
     *
     * @param caller 注入进来的身份。这里**跑在模型线程**，读不到 SecurityContext，
     *               只能靠这个参数；为 null 时按匿名处理并直接拒绝，绝不退化成「看全部」。
     */
    public String search(String keyword, Integer limit, AiCaller caller) {
        AiCaller who = AiCaller.orAnonymous(caller);
        if (!who.isAuthenticated()) {
            return AiCaller.ANONYMOUS_MESSAGE;
        }
        int cap = limit == null ? DEFAULT_SEARCH_LIMIT : Math.min(Math.max(limit, 1), MAX_SEARCH_LIMIT);
        String kw = keyword == null ? "" : keyword.strip();
        boolean hasKw = !kw.isEmpty();

        StringBuilder sql = new StringBuilder("SELECT t.task_id, t.task_name, t.status, t.created_at, ")
                .append(SECTION_COUNT)
                .append(" FROM tasks t WHERE t.deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        appendUserFilterIfNeeded(sql, args, who);
        if (hasKw) {
            sql.append(" AND (t.task_name ILIKE ? ESCAPE '\\' OR t.task_id = ?)");
            args.add("%" + escapeLike(kw) + "%");
            args.add(kw);
        }
        sql.append(" ORDER BY t.updated_at DESC NULLS LAST, t.id DESC LIMIT ?");
        args.add(cap);

        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        } catch (RuntimeException e) {
            log.warn("[task-context] search_tasks 查询失败: {}", e.getMessage());
            return "任务检索失败：" + e.getMessage() + "。请把错误原样告诉用户，不要编造任务ID。";
        }

        if (rows.isEmpty()) {
            if (hasKw) {
                return "没有名字匹配「" + kw + "」的任务。请让用户确认任务名（可能是简称/错字），"
                        + "或在不带关键词的情况下调用本工具查看最近任务；不要凭猜想编造 task_id。";
            }
            return "库里当前没有任何任务。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(hasKw
                ? "按关键词「" + kw + "」匹配到 " + rows.size() + " 个任务：\n"
                : "最近的 " + rows.size() + " 个任务：\n");
        for (Map<String, Object> row : rows) {
            sb.append("- ").append(taskLine(row)).append('\n');
        }
        sb.append("提示：任务名没有唯一约束，可能重名。返回多条时把清单原样列给用户挑选，")
                .append("不要自行认定其中一条；也不要编造清单之外的 task_id。");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 共用片段

    /** 断面数子查询：同一个任务下有多少条断面（软删不算）。模型据此判断「这个任务有没有跑过」。 */
    private static final String SECTION_COUNT =
            "(SELECT count(*) FROM cross_sections cs WHERE cs.task_id = t.task_id AND cs.deleted_at IS NULL)"
                    + " AS section_count";

    /**
     * 追加用户归属条件。这是**整个任务检索的唯一过滤点**，因此三重保险都压在这：
     * <ol>
     *   <li>{@link AiCaller#seeAll()} 为真（已认证 + 管理员）→ 不加条件，看全部；</li>
     *   <li>普通用户 → 加 {@code t.user_id = ?}；</li>
     *   <li>身份缺失（匿名 / 注入失败）→ 加 <b>恒假条件</b> {@code AND 1 = 0}。
     *       这一条是 fail-closed 的兜底：宁可查到 0 行，也绝不因为「忘了过滤」而放开。</li>
     * </ol>
     *
     * <p>刻意不用 {@code AND (user_id = CAST(? AS BIGINT) OR CAST(? AS BIGINT) IS NULL)} 那种写法：
     * 那要把 null 交给 JdbcTemplate，靠驱动去推断类型，踩一次「无法确定参数类型」就很难查。
     * 条件式拼接没有可空参数，语义也更直白。
     */
    private static void appendUserFilterIfNeeded(StringBuilder sql, List<Object> args, AiCaller caller) {
        if (caller.seeAll()) {
            return;
        }
        Long userId = caller.userId();
        if (userId == null) {
            sql.append(" AND 1 = 0");
            return;
        }
        sql.append(" AND t.user_id = ?");
        args.add(userId);
    }

    // ------------------------------------------------------------------ 归属校验（供工具层用）

    /**
     * 这个任务归不归当前调用方。
     *
     * <p>工具层在拿到 {@code task_id} 之后必须过这一关：早先 {@code query_risk_data}
     * 的 SQL 只有 {@code WHERE r.task_id = ?}，没有归属条件 —— 只要知道 id 就能读别人的数据。
     *
     * @return false 同时覆盖「任务不存在」「已删除」「不属于你」三种情况（对外不区分，防枚举）
     */
    public boolean canAccessTask(String taskId, AiCaller caller) {
        AiCaller who = AiCaller.orAnonymous(caller);
        if (!who.isAuthenticated()) {
            return false;
        }
        if (who.seeAll()) {
            return existsAlive("SELECT 1 FROM tasks t WHERE t.task_id = ? AND t.deleted_at IS NULL", taskId);
        }
        Long owner = queryOwner("SELECT t.user_id FROM tasks t WHERE t.task_id = ? AND t.deleted_at IS NULL", taskId);
        return who.allowsOwner(owner) && owner != null;
    }

    /**
     * 这个断面归不归当前调用方（沿 {@code cross_sections → tasks} 找归属）。
     *
     * <p>{@code cross_sections} 自己也有 {@code user_id}，但任务复制、断面重算等路径写入的
     * 归属以任务为准更稳，所以这里统一用任务的 owner 判定。
     */
    public boolean canAccessSection(String sectionId, AiCaller caller) {
        AiCaller who = AiCaller.orAnonymous(caller);
        if (!who.isAuthenticated()) {
            return false;
        }
        String sql = "SELECT t.user_id FROM cross_sections cs"
                + " JOIN tasks t ON cs.task_id = t.task_id"
                + " WHERE cs.section_id = ? AND cs.deleted_at IS NULL AND t.deleted_at IS NULL";
        if (who.seeAll()) {
            return existsAlive("SELECT 1 FROM cross_sections cs JOIN tasks t ON cs.task_id = t.task_id"
                    + " WHERE cs.section_id = ? AND cs.deleted_at IS NULL AND t.deleted_at IS NULL", sectionId);
        }
        Long owner = queryOwner(sql, sectionId);
        return who.allowsOwner(owner) && owner != null;
    }

    /** 只问「存不存在」，不关心归属。查库异常一律当作不存在（fail-closed）。 */
    private boolean existsAlive(String sql, String key) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, key);
            return !rows.isEmpty();
        } catch (RuntimeException e) {
            log.warn("[task-context] 归属校验查询失败，按拒绝处理: {}", e.getMessage());
            return false;
        }
    }

    /** 取数据行的 owner；查不到或异常返回 null（调用方据此拒绝）。 */
    private Long queryOwner(String sql, String key) {
        try {
            List<Long> owners = jdbcTemplate.queryForList(sql, Long.class, key);
            return owners.isEmpty() ? null : owners.get(0);
        } catch (RuntimeException e) {
            log.warn("[task-context] 归属查询失败，按拒绝处理: {}", e.getMessage());
            return null;
        }
    }

    /** 一行任务描述：名称 / task_id / 状态 / 断面数 / 创建时间。两个入口共用，避免口径漂移。 */
    static String taskLine(Map<String, Object> row) {
        Object name = row.get("task_name");
        String title = name == null || String.valueOf(name).isBlank() ? "（未命名任务）" : String.valueOf(name);
        Object status = row.get("status");
        Object sections = row.get("section_count");
        return String.format("任务「%s」 task_id: %s ｜ 状态: %s ｜ 断面数: %s ｜ 创建: %s",
                title,
                row.get("task_id"),
                status == null ? "未知" : status,
                sections == null ? "0" : sections,
                timeText(row.get("created_at")));
    }

    private static String timeText(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().format(TIME_FMT);
        }
        if (value instanceof java.util.Date d) {
            return new Timestamp(d.getTime()).toLocalDateTime().format(TIME_FMT);
        }
        return value == null ? "未知" : String.valueOf(value);
    }

    /** LIKE 的元字符要转义：任务名里带 % 或 _ 时不该被当成通配符。 */
    static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
