package com.xander.lab.integration.mcp.csdn.application;

import com.xander.lab.payment.api.BillingBiz;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.integration.mcp.security.McpOAuthRequestContext;
import com.xander.lab.integration.mcp.csdn.application.CsdnAuthorizationService;
import com.xander.lab.integration.mcp.csdn.application.CsdnBlogSyncService;
import com.xander.lab.content.api.PublishResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Applies CSDN MCP commands for the user bound to the calling MCP client. */
@Service
@RequiredArgsConstructor
public class CsdnMcpApplicationService {
    private final CsdnAuthorizationService csdnAuthorizationService;
    private final CsdnBlogSyncService csdnBlogSyncService;
    private final BillingPort billingPort;

    public CsdnAuthorizationService.AuthorizationView authorizationStatus() {
        return csdnAuthorizationService.status(requireUserId());
    }

    public CsdnAuthorizationService.AuthorizationView startAuthorization() {
        return csdnAuthorizationService.start(requireUserId());
    }

    public PublishResult syncOwnedPost(Long postId) {
        Long userId = requireUserId();
        PublishResult result = csdnBlogSyncService.sync(userId, postId);
        billingPort.chargeFlat(userId, BillingBiz.CSDN_PUBLISH, "csdn:" + postId,
                "post:" + postId, billingPort.flatPoints(BillingBiz.CSDN_PUBLISH), "CSDN 发布成功");
        return result;
    }

    public Long currentUserId() {
        return McpOAuthRequestContext.userId();
    }

    private Long requireUserId() {
        Long userId = currentUserId();
        if (userId == null) throw new McpAuthorizationRequiredException();
        return userId;
    }

    /** Raised when the MCP caller has not completed DinQorAI authorization. */
    public static class McpAuthorizationRequiredException extends RuntimeException { }
}
