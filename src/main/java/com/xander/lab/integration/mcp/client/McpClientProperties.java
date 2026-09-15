package com.xander.lab.integration.mcp.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 出站 MCP 客户端配置。
 *
 * <p>默认关闭：未开启时不会发起任何出站连接，远端工具也不会出现在工具面里。
 * 与 {@code mcp.oauth} / {@code mcp.blog} 那套服务端开关是彼此独立的两套东西——
 * 那边是本平台对外提供 MCP 服务，这边是本平台连出去用别人的 MCP 服务。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.client")
public class McpClientProperties {

    /** 总开关；关闭时连接工厂拒绝建连，工具来源直接返回空列表。 */
    private boolean enabled = false;

    /** 附加请求头加密密钥，Base64 编码的 32 字节；启用时必填。 */
    private String credentialEncryptionKey = "";

    /** 建连超时。远端不可达时必须快速失败，不能挂住整轮对话。 */
    private int connectTimeoutSeconds = 10;

    /** 单次远端工具调用超时。 */
    private int requestTimeoutSeconds = 30;

    /** 单台服务器最多并入多少个远端工具，防止一台服务器的工具面把上下文撑爆。 */
    private int maxToolsPerServer = 50;

    /** 远端工具描述的截断长度。远端描述不受本平台控制，必须截断后再进提示词。 */
    private int maxDescriptionLength = 400;

    /**
     * 单次远端工具返回给模型的字符数上限。
     *
     * <p>远端返回体的大小完全不受本平台控制，一次返回几 MB 就能把整轮上下文挤爆。
     * 超限时截断并显式标注，让模型知道结果不完整，而不是以为那就是全部。</p>
     */
    private int maxResultLength = 8000;

    /**
     * 工具名总长度上限。
     *
     * <p>必须与 {@code agent_tool_invocation.tool_name}、{@code agent_approval.tool_name}
     * 的列宽保持一致，否则远端工具名过长会在落库时炸掉整轮执行。</p>
     */
    private int maxToolNameLength = 64;
}
