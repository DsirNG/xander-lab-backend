package com.xander.lab.agent.application;

import java.util.List;

/**
 * 运行期才知道有哪些工具的来源。
 *
 * <p>内置工具都是 {@code @Component}，启动期就被 {@link AgentToolRegistry} 一次收齐。
 * 但有一类工具的存在取决于配置：接了哪几台远端 MCP 服务器、对方提供了哪些工具，
 * 都要读配置才知道。这类来源走本接口，由注册表在合并视图时拉取。</p>
 *
 * <p><b>实现必须廉价。</b>注册表每一轮渲染工具面都会读一次，实现内部要自己缓存；
 * 不能每次调用都去连远端或查库——那会把每一轮模型调用都变成一次网络往返。</p>
 */
public interface AgentDynamicToolSource {

    /**
     * 该用户可用的动态工具。
     *
     * <p>动态工具是有归属的：平台级配置人人可用，用户自己配的只能自己用。
     * 所以这里带 userId 而不是返回一份全局列表——否则甲的服务器会出现在乙的工具面里。</p>
     *
     * @param userId 归属用户；{@code null} 表示不限定用户，返回全部动态工具
     *               （用于 {@code names()} 这类不区分用户的场景）
     */
    List<AgentTool> dynamicTools(Long userId);
}
