package com.xander.lab.integration.mcp.dual.application;

import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostInput;
import com.xander.lab.integration.mcp.blog.application.BlogMcpApplicationService;
import com.xander.lab.integration.mcp.csdn.application.CsdnMcpApplicationService;
import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.integration.mcp.csdn.application.CsdnAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Publishes one generated article to the local blog first, then to the owner's CSDN account. */
@Service
@RequiredArgsConstructor
public class DualPublishMcpApplicationService {
    private final BlogMcpApplicationService blogMcpService;
    private final CsdnMcpApplicationService csdnMcpService;

    /**
     * Uses durable local publication as the first step. A CSDN failure leaves the local article intact
     * and returns its ID so the caller can retry the CSDN synchronization safely.
     */
    public DualPublishResult publish(ContentPostInput request) {
        ContentPost localPost = blogMcpService.create(request, true);
        try {
            BlogSyncResult csdn = csdnMcpService.syncOwnedPost(localPost.id());
            return new DualPublishResult(localPost.id(), true, csdn.externalId(), csdn.url(), null);
        } catch (RuntimeException exception) {
            return new DualPublishResult(localPost.id(), false, null, null,
                    exception.getMessage() == null ? "CSDN publication failed" : exception.getMessage());
        }
    }

    public boolean isAuthorized() { return blogMcpService.isAuthorized(); }

    public CsdnAuthorizationService.AuthorizationView csdnAuthorizationStatus() {
        return csdnMcpService.authorizationStatus();
    }

    public CsdnAuthorizationService.AuthorizationView startCsdnAuthorization() {
        return csdnMcpService.startAuthorization();
    }

    public record DualPublishResult(Long localPostId, boolean csdnPublished, String csdnExternalId,
                                    String csdnUrl, String csdnError) { }
}
