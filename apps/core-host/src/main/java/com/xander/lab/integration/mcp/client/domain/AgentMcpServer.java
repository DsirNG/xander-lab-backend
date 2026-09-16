package com.xander.lab.integration.mcp.client.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一台可连接的远端 MCP 服务器配置。 */
@Data
@TableName("agent_mcp_server")
public class AgentMcpServer {

    /** 平台级配置的归属用户ID：管理员维护，全部用户可用。 */
    public static final long PLATFORM_OWNER_ID = 0L;

    public static final String STATUS_OK = "OK";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerUserId;

    private String serverKey;

    private String displayName;

    private String endpointUrl;

    /** 附加请求头 JSON 的 AES-GCM 密文；明文只在建连瞬间存在于内存。 */
    private String headersEncrypted;

    private Boolean enabled;

    /** 上次探测到的工具目录缓存。渲染工具面时读这里，不发网络请求。 */
    private String toolsJson;

    private String lastStatus;

    private String lastError;

    private LocalDateTime lastConnectedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public boolean isPlatformOwned() {
        return ownerUserId != null && ownerUserId == PLATFORM_OWNER_ID;
    }
}
