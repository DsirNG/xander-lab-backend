package com.xander.lab.integration.mcp.csdn.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Encrypted CSDN browser credential owned by one application user. */
@Data
@TableName("csdn_platform_credential")
public class CsdnPlatformCredential {
    @TableId(type = IdType.AUTO) private Long id;
    private Long userId;
    private String encryptedCookie;
    private String csdnUsername;
    private String status;
    private LocalDateTime authorizedAt;
    private LocalDateTime lastValidatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
