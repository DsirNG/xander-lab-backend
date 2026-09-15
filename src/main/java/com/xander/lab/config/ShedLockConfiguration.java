package com.xander.lab.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 多实例定时任务互斥（V9 §67 / §68 / §69）。
 *
 * <h3>为什么默认关闭</h3>
 * <p>Wave 0A 是<b>单实例</b>部署，现有的 DB 原子领取已经保证"恰好一次"，
 * 加锁不会带来任何新能力，只会多一个失败点（锁表缺失 / 锁未释放）。
 * 锁是<b>扩到多实例时才需要</b>的能力，所以这里用 {@code shedlock.enabled}
 * 显式开启，与 {@code auth.refresh.enabled} 同一套路子：
 * Wave 0A 交付机制与门禁，切换点留给真正需要它的那一波。</p>
 *
 * <p>关闭时 {@code @SchedulerLock} 注解<b>被完全忽略</b>（没有拦截器），
 * 任务按原来的方式在每个实例上执行 —— 也就是现状，不会静默改变行为。</p>
 *
 * <h3>为什么用数据库时间</h3>
 * <p>{@code usingDbTime()} 让锁的到期判断走 MySQL 的时钟，而不是各实例的 JVM 时钟。
 * 实例之间哪怕只有几百毫秒的时钟偏移，用本地时间判"锁是否到期"都会出现
 * 两个实例同时认为自己持锁的窗口 —— 而这正是锁要防的事情。</p>
 *
 * <h3>为什么锁表在 MySQL 而不是 Redis</h3>
 * <p>V9 §41：认证维护类任务（refresh history 清理等）不能依赖 Redis，
 * 否则一次 Redis 故障会连带停掉所有后台清理，让表无限增长。
 * 锁表 DDL 见 {@code migrations/versioned/V2026091505__shedlock.sql}。</p>
 *
 * <h3>与 {@code execution_scope} 的关系</h3>
 * <p>只有 {@code execution_scope = global_once} 的任务才允许加锁。
 * 给 {@code per_instance} 任务加全局锁会让 N-1 个实例的本地状态永不刷新，
 * 而"不重复执行"的验收指标仍然通过 —— 一个不会失败的失败（V9 §69）。
 * 门禁 {@code scheduler-lock-scope-consistency} 强制这条规则。</p>
 */
@Configuration
@ConditionalOnProperty(name = "shedlock.enabled", havingValue = "true")
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class ShedLockConfiguration {

    /**
     * 锁的兜底上限：实例在持锁期间崩溃时，锁最多被占用 5 分钟。
     *
     * <p>单个任务用 {@code @SchedulerLock(lockAtMostFor = ...)} 覆盖它。
     * 取值规则：必须<b>大于该任务最坏运行时长</b>（否则任务还在跑锁就到期，
     * 第二个实例会并发执行），但也不能大到让崩溃后的恢复等待过久。</p>
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
