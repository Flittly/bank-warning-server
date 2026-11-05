package com.yangtze.bankwarning.ai.context;

import com.yangtze.bankwarning.security.security.SecurityUtils;

/**
 * 「这次对话是谁发起的」—— 不是 MyBatis 参数，而是**跨线程传递的身份信物**。
 *
 * <h2>为什么需要它</h2>
 * 用户身份在 Spring Security 里存在 {@code SecurityContextHolder}，底层是
 * <b>ThreadLocal</b>：只有处理 HTTP 请求的那个 Tomcat 线程读得到。模型 ReAct 循环里的
 * 工具调用跑在另一条执行链上（异步流式 / 线程池），ThreadLocal 是空的。
 *
 * <p>而 {@code SecurityUtils.getCurrentUserIdForDataFilter()} 在「读不到身份」和
 * 「我是管理员」两种情况下都返回 {@code null}，调用方一律解释成「不过滤」⇒ 普通用户
 * 调 {@code search_tasks} 能搜到别人的任务。这是典型的 <b>fail-open</b>。
 *
 * <h2>怎么传进来的</h2>
 * {@code AgentController.prepare()} 仍处在 Tomcat 线程（SecurityContext 还在），
 * 在那里取 {@link SecurityUtils.DataScope} 造一个本对象，放进
 * {@code RuntimeContext}；AgentScope 的 {@code ToolMethodInvoker.resolveContextParameter}
 * 看到工具方法里有**不带 {@code @ToolParam} 的非 java.* 类型参数**，就按类型从
 * RuntimeContext 里取出来注入 —— 不进 JSON Schema，模型既看不见也改不了。
 *
 * <h2>使用纪律</h2>
 * <ul>
 *   <li><b>永远 fail-closed</b>：注入失败（工具参数为 null）时按
 *       {@link #anonymous()} 处理并拒绝，绝不能退化成 seeAll()。</li>
 *   <li><b>不要暴露存在性</b>：「无权访问」与「任务不存在」对外回同一句话术，
 *       否则普通用户能靠回显差异枚举出别人的 task_id。</li>
 * </ul>
 */
public final class AiCaller {

    /** 身份未解析出来（注入失败 / 未登录）时的兜底实例：什么都看不到。 */
    private static final AiCaller ANONYMOUS = new AiCaller(false, false, null);

    /** 无权访问与不存在共用的对外话术 —— 两者必须无法区分，防止枚举探测。 */
    public static final String NOT_FOUND_MESSAGE = "未查询到对应的数据：该对象可能已被删除，或当前账号无权访问。";

    /** 身份未解析出来时的统一话术。 */
    public static final String ANONYMOUS_MESSAGE = "当前对话没有携带有效的登录身份，无法检索业务数据。请重新登录后再试。";

    private final boolean authenticated;
    private final boolean admin;
    private final Long userId;

    private AiCaller(boolean authenticated, boolean admin, Long userId) {
        this.authenticated = authenticated;
        this.admin = admin;
        this.userId = userId;
    }

    /**
     * 从请求线程抓到的 {@link SecurityUtils.DataScope} 转成注入用信物。
     *
     * @param scope 为 null 时返回 {@link #anonymous()}（不信任 null）
     */
    public static AiCaller of(SecurityUtils.DataScope scope) {
        if (scope == null || !scope.authenticated()) {
            return ANONYMOUS;
        }
        return new AiCaller(true, scope.admin(), scope.userId());
    }

    /** 身份没拿到。任何数据访问都应直接拒绝。 */
    public static AiCaller anonymous() {
        return ANONYMOUS;
    }

    /** 仅测试/离线脚本用：显式构造一个普通用户。 */
    public static AiCaller forUser(long userId) {
        return new AiCaller(true, false, userId);
    }

    /** 仅测试/离线脚本用：显式构造一个管理员。 */
    public static AiCaller forAdmin() {
        return new AiCaller(true, true, null);
    }

    /** 工具参数为 null 时的安全兜底：一律当作匿名，绝不当作管理员。 */
    public static AiCaller orAnonymous(AiCaller caller) {
        return caller == null ? ANONYMOUS : caller;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public boolean isAdmin() {
        return authenticated && admin;
    }

    public Long userId() {
        return userId;
    }

    /** 是否能看到全部数据（未认证永远 false）。 */
    public boolean seeAll() {
        return authenticated && admin;
    }

    /**
     * 能否查看「归属某个用户」的一条数据。
     *
     * @param ownerUserId 数据行的 user_id
     * @return 管理员 true；普通用户仅在 owner 与自身相等时 true；
     *         owner 为 null（历史脏数据）时只有管理员能看到
     */
    public boolean allowsOwner(Long ownerUserId) {
        if (!authenticated) {
            return false;
        }
        if (admin) {
            return true;
        }
        return userId != null && userId.equals(ownerUserId);
    }

    @Override
    public String toString() {
        // 故意不打印 userId 之外的任何凭证信息；只用于日志排查
        return "AiCaller{authenticated=" + authenticated + ", admin=" + admin + ", userId=" + userId + '}';
    }
}
