package com.xander.lab.agent.tool.mcp.application;

import com.xander.lab.agent.tool.mcp.api.AgentMcpServerRequest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 服务器管理的事务边界。
 *
 * <p>本类只守一件事：<b>做外部 MCP 往返的方法不得在事务里</b>。
 * 这件事只能靠注解反射守 —— 纯单测拿不到 Spring 代理，
 * 而把 {@code @Transactional} 加回 {@code probe} 之后所有功能测试仍然全绿，
 * 症状只在远端慢时出现（事务长时间持有数据库连接 → 连接池耗尽）。</p>
 */
class AgentMcpServerServiceTest {

    /**
     * {@code probe} 方法体第一件事就是 {@code connectionFactory.listTools} ——
     * 一次真实的 {@code java.net.http} 往返。圈进事务有两个后果：
     *
     * <ul>
     *   <li>远端慢 → 事务长时间持有数据库连接；</li>
     *   <li>而且事务<b>换不来任何原子性</b>：远端副作用已经发生，本地回滚撤销不了它。</li>
     * </ul>
     *
     * <p>去掉事务是安全的：方法体里只有一条写语句（{@code mapper.updateById}），
     * 它本身就是原子的。</p>
     */
    @Test
    void probeMustNotBeTransactional() throws NoSuchMethodException {
        Method probe = AgentMcpServerService.class.getMethod("probe", Long.class, long.class);

        assertThat(probe.getAnnotation(Transactional.class))
                .as("probe 不得带 @Transactional —— 它在事务体开头就做外部 MCP HTTP 往返，"
                        + "远端慢会长时间占用数据库连接；而事务并不能让远端调用变原子，"
                        + "只会把锁窗口撑大")
                .isNull();
    }

    /**
     * 另外四个方法只碰本地库（外加内存缓存失效），保持事务是对的。
     *
     * <p>这条与上一条一起钉住"哪些方法该有、哪些不该有" —— 否则下一次修订很容易
     * 为了"统一风格"把它们一刀切地全删或全加。特别是 {@code create}/{@code update}
     * 里的"查重 + 插入"需要同事务才能避免并发下的同名标识重复。</p>
     */
    @Test
    void localOnlyMethodsKeepTheirTransaction() throws NoSuchMethodException {
        assertThat(AgentMcpServerService.class
                .getMethod("create", long.class, AgentMcpServerRequest.class)
                .getAnnotation(Transactional.class))
                .as("create 的『查重 + 插入』需要同事务，否则并发下会写入同名标识")
                .isNotNull();

        assertThat(AgentMcpServerService.class
                .getMethod("update", Long.class, long.class, AgentMcpServerRequest.class)
                .getAnnotation(Transactional.class))
                .as("update 是单条条件更新，但保持事务与 create/delete 一致")
                .isNotNull();

        assertThat(AgentMcpServerService.class
                .getMethod("delete", Long.class, long.class)
                .getAnnotation(Transactional.class))
                .as("delete 应保持事务")
                .isNotNull();

        Transactional list = AgentMcpServerService.class
                .getMethod("list", long.class)
                .getAnnotation(Transactional.class);
        assertThat(list).as("list 是纯读，应保留只读事务").isNotNull();
        assertThat(list.readOnly()).as("list 不应变成读写事务").isTrue();
    }
}
