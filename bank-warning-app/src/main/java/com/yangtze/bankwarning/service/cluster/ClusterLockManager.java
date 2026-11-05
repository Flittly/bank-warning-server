package com.yangtze.bankwarning.service.cluster;

/**
 * 跨实例的**读写锁**（全部非阻塞）。
 *
 * <p><b>为什么是读写锁而不是普通互斥锁</b>：这个项目里对「一个任务」有两类操作，
 * 它们的关系不是「互斥」而是「共享 / 独占」：
 * <table border="1">
 *   <tr><th>操作</th><th>锁模式</th><th>为什么</th></tr>
 *   <tr><td>整任务运行 {@code runTask}</td><td>独占</td>
 *       <td>它第一步就 {@code clearTaskResults} 把该任务全部旧结果软删，再逐条重写</td></tr>
 *   <tr><td>单断面重算 {@code recomputeSection}</td><td>共享</td>
 *       <td>只写自己那一条断面；同任务下两条不同断面同时重算井水不犯河水</td></tr>
 * </table>
 * 用普通互斥锁只能表达「都互斥」，会把「同时重算两条不同断面」也挡掉 —— 那本来是可以并行的。
 * 读写锁恰好：整任务独占时挡掉所有单算；有单算在跑时挡住整任务；单算之间互不阻挡。
 *
 * <p><b>这一把锁同时替掉了原来两张表</b>：
 * <ul>
 *   <li>{@code SectionRecomputeGuard.hasInFlightForTask(taskId)} —— 原先是「本进程有没有断面在单算」，
 *       现在等于问「这把任务锁的共享模式是否被占用」，**天然跨实例可见**；</li>
 *   <li>{@code SectionRecomputeGuard} 里那张 {@code sectionId -> taskId} 的在途表 —— 原先只在单 JVM 内
 *       防同一断面重复提交，现在换成断面级的独占锁，同样跨实例生效。</li>
 * </ul>
 *
 * <p><b>为什么只提供非阻塞语义</b>：三个加锁点都属于「重复做只会白烧模型调用」，
 * 让后来者立刻收到 409 比让它在 HTTP 连接上排队 900 秒更诚实。既然语义只有一个，
 * 就不必伪装成通用锁。
 *
 * <p><b>实现靠 {@code bw.cluster.enabled} 切换</b>：默认 {@link LocalClusterLockManager}（进程内），
 * 开启后 {@link RedissonClusterLockManager}（Redis）。接口语义完全一致，调用方零改动。
 *
 * <p><b>不可重入</b>：同一 key 已被独占时再要独占会失败；共享模式可以重复获取（内部计数）。
 * 现有调用点都是「进方法拿一次、finally 放一次」，不存在重入需求。
 */
public interface ClusterLockManager {

    /**
     * 以**共享**模式占用 key（立刻返回）。
     *
     * @return 成功返回句柄（必须在 finally 里 close）；已被独占时返回 {@code null}
     * @throws RuntimeException Redis 不可用时抛出 —— 刻意 fail-closed，
     *         不能把「连不上 Redis」伪装成「有人占用」，那会让用户看到误导性的 409
     */
    LockHandle tryLockShared(String key);

    /**
     * 以**独占**模式占用 key（立刻返回）。
     *
     * @return 成功返回句柄（必须在 finally 里 close）；已被独占或仍有共享持有者时返回 {@code null}
     */
    LockHandle tryLockExclusive(String key);

    /** 该 key 此刻是否被任何人（任意实例、任意模式）持有，仅用于诊断日志与测试。 */
    boolean isHeld(String key);

    /**
     * 锁的持有凭证。{@code close()} 幂等。
     *
     * <p>用 {@link AutoCloseable} 而不是自定义 {@code release()}，是为了能写成
     * {@code try (var handle = ...)}，让「拿锁」与「放锁」在语法上无法被拆开。
     */
    interface LockHandle extends AutoCloseable {
        String key();

        boolean exclusive();

        @Override
        void close();
    }
}
