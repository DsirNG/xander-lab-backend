package com.xander.lab.agent.api;

/**
 * 技能编排界面用来挑选工具的一项。
 *
 * <p>只暴露名字与用途，不含参数 schema 与实现细节：技能里的 {@code toolNames}
 * 只是"这类活通常用这些工具"的提示，用户需要的是"有哪些工具、各自干什么"，
 * 而不是一份可以直接照着构造调用的规格。真正的调用约束仍由服务端工具表决定。</p>
 */
public record AgentToolOption(String name, String description) {
}
