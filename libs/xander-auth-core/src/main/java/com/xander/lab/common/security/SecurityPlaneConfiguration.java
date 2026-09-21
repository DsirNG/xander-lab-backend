package com.xander.lab.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 安全平面（Security Plane）的本地组件装配。
 *
 * <p>三个组件都必须<b>每实例一份</b>，所以是普通单例 Bean，不能用任何分布式单例机制包装。</p>
 *
 * <p>它们放在共享内核里是刻意的：Gateway（WebFlux）、core-host、agent、
 * content-automation 都要用同一套语义。本包只依赖 JDK，不依赖 Spring MVC、
 * MyBatis 或任何业务域 —— 这样 Gateway 依赖它不会引入
 * {@code spring-boot-starter-web}（那会直接让 WebFlux 应用启动失败）。</p>
 */
@Configuration
public class SecurityPlaneConfiguration {

    /** 本实例的已封禁账号集合与增量游标。 */
    @Bean
    public SecurityStateCache securityStateCache() {
        return new SecurityStateCache();
    }

    /** 本实例的活跃 SSE / WS 注册表。 */
    @Bean
    public ConnectionRegistry connectionRegistry() {
        return new ConnectionRegistry();
    }

    /**
     * 本实例的安全平面就绪标记。
     *
     * <p>初始态是 {@code NOT_READY}，写入者只允许是当前宿主自己的安全平面
     * bootstrap/reconciler；消费者是宿主 readiness 探针与长连接发送边界。</p>
     */
    @Bean
    public SecurityPlaneReadiness securityPlaneReadiness() {
        return new SecurityPlaneReadiness();
    }
}
