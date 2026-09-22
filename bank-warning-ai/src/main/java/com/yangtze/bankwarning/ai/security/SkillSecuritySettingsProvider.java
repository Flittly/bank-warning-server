package com.yangtze.bankwarning.ai.security;

/**
 * 安全档位生效值的提供者。
 *
 * <p>三层防护的各组件只依赖这个接口读参数，不依赖具体的服务实现 ——
 * 既避免了 {@code security → service} 的包级循环，也让单元测试可以直接给一个
 * {@code () -> SkillSecuritySettings.preset(...)} 的桩，不必构造整条 Spring 依赖链。
 *
 * <p>实现方必须保证：<b>每次调用都返回当前生效值</b>（而不是构造期快照），
 * 否则「切档位免重启生效」这个核心性质就不成立。
 */
@FunctionalInterface
public interface SkillSecuritySettingsProvider {

    /** 当前生效的档位参数 */
    SkillSecuritySettings settings();
}
