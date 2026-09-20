package com.xander.lab.agent.tool.mcp.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/** 新增或修改一台远端 MCP 服务器。 */
@Data
public class AgentMcpServerRequest {

    /**
     * 服务器标识，会拼进工具名（{@code mcp__<serverKey>__<tool>}）。
     *
     * <p>限 2-20 位：工具名整体受 {@code agent_tool_invocation.tool_name} 的 64 字符列宽约束，
     * 标识留得太长会把远端工具名挤到超限，那些工具就永远进不了工具面。</p>
     */
    @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{1,19}$")
    private String serverKey;

    @NotBlank @Size(max = 120)
    private String displayName;

    @NotBlank @Size(max = 500)
    private String endpointUrl;

    /**
     * 附加请求头，明文传入、落库前加密。
     *
     * <p>约定：{@code null} 表示不修改（改配置时留空不会误删已有凭据）；
     * 提交空对象 {@code {}} 表示显式清空。这是为了让"只改名字"这种操作不会顺手把凭据抹掉。</p>
     */
    @Size(max = 20)
    private Map<@NotBlank @Size(max = 120) String, @Size(max = 2000) String> headers;

    private Boolean enabled = false;
}
