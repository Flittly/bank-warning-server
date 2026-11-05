package com.yangtze.bankwarning.ai.context;

import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归守卫：身份信物的判定口径。
 *
 * <p>锁住的核心是一条：**「读不到身份」绝不能等价于「管理员」**。
 * 早先 {@code SecurityUtils.getCurrentUserIdForDataFilter()} 两种情况都返回 null，
 * 而 SQL 侧「null ⇒ 不加过滤条件」⇒ 工具线程里读不到身份时，普通用户会看到全库任务。
 */
@DisplayName("AiCaller：身份判定必须 fail-closed")
class AiCallerTest {

    @Test
    @DisplayName("匿名：看不到任何数据，也放不过任何归属")
    void anonymousSeesNothing() {
        AiCaller anon = AiCaller.anonymous();

        assertFalse(anon.isAuthenticated());
        assertFalse(anon.isAdmin());
        assertFalse(anon.seeAll(), "匿名绝不能 seeAll —— 这一条就是 fail-open 的唯一入口");
        assertNull(anon.userId());
        assertFalse(anon.allowsOwner(1L), "匿名不该放过任何归属");
        assertFalse(anon.allowsOwner(null));
    }

    @Test
    @DisplayName("注入失败（null）按匿名处理，绝不当成管理员")
    void nullCallerDegradesToAnonymous() {
        AiCaller who = AiCaller.orAnonymous(null);

        assertFalse(who.isAuthenticated());
        assertFalse(who.seeAll());
        assertSame(AiCaller.anonymous(), who);
    }

    @Test
    @DisplayName("普通用户：只能看自己名下的数据")
    void userSeesOnlyOwnData() {
        AiCaller user = AiCaller.forUser(7L);

        assertTrue(user.isAuthenticated());
        assertFalse(user.seeAll());
        assertTrue(user.allowsOwner(7L));
        assertFalse(user.allowsOwner(8L), "不能看别人的");
        assertFalse(user.allowsOwner(null), "归属缺失的历史脏数据只有管理员能看到");
    }

    @Test
    @DisplayName("管理员：看全部，包含归属缺失的行")
    void adminSeesAll() {
        AiCaller admin = AiCaller.forAdmin();

        assertTrue(admin.isAuthenticated());
        assertTrue(admin.isAdmin());
        assertTrue(admin.seeAll());
        assertTrue(admin.allowsOwner(7L));
        assertTrue(admin.allowsOwner(null));
    }

    @Test
    @DisplayName("DataScope 转换：未认证的 scope 不会变成管理员")
    void scopeConversionKeepsFailClosed() {
        assertSame(AiCaller.anonymous(), AiCaller.of(null), "null scope 必须按匿名处理");
        assertSame(AiCaller.anonymous(), AiCaller.of(SecurityUtils.DataScope.ANONYMOUS));

        // 构造一个「自称 admin 但 authenticated=false」的 scope：这种矛盾输入必须被压成匿名
        SecurityUtils.DataScope inconsistent = new SecurityUtils.DataScope(false, true, null);
        assertFalse(AiCaller.of(inconsistent).seeAll(),
                "authenticated=false 时即使 admin 标记为 true 也不能放开");
    }

    @Test
    @DisplayName("DataScope.seeAll 自身也要求 authenticated")
    void dataScopeSeeAllRequiresAuth() {
        assertFalse(SecurityUtils.DataScope.ANONYMOUS.seeAll());
        assertFalse(new SecurityUtils.DataScope(false, true, null).seeAll());
        assertTrue(new SecurityUtils.DataScope(true, true, null).seeAll());
        assertFalse(new SecurityUtils.DataScope(true, false, 3L).seeAll());
    }
}
