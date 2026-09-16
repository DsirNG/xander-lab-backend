package com.xander.lab.integration.mcp.juejin.application;

import com.xander.lab.billing.application.BillingService;
import com.xander.lab.payment.api.BillingBiz;
import com.xander.lab.integration.mcp.security.McpOAuthRequestContext;
import com.xander.lab.publishing.api.BlogPostDTO;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.content.api.PublishResult;
import com.xander.lab.integration.mcp.blog.application.BlogMcpApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Applies Juejin MCP commands for the user bound to the calling MCP client.
 * Publish uses durable local publication first (unpublished draft) and then the
 * Juejin sync, so a Juejin failure leaves the local draft intact for a safe retry.
 */
@Service
@RequiredArgsConstructor
public class JuejinMcpApplicationService {
    private final BlogMcpApplicationService blogMcpService;
    private final JuejinBlogSyncService juejinBlogSyncService;
    private final JuejinCredentialService credentials;
    private final BillingService billingService;

    /** 掘金授权状态：AUTHORIZED=可用凭据；EXPIRED=登录态失效待重新授权；其余 NOT_AUTHORIZED。 */
    public String authorizationStatus() {
        Long userId = requireUserId();
        if (credentials.isAuthorized(userId)) return "AUTHORIZED";
        if (credentials.isExpired(userId)) return "EXPIRED";
        return "NOT_AUTHORIZED";
    }

    /**
     * Publishes a raw article to Juejin. A local blog draft is created first so the
     * publication attempt is durable and idempotent, then the draft is synced to Juejin.
     */
    public PublishResult publishArticle(String title, String content, String summary,
                                        String juejinCategoryId, List<String> juejinTagIds) {
        Long userId = requireUserId();
        BlogPostDTO request = new BlogPostDTO();
        request.setTitle(title);
        request.setContent(content);
        request.setSummary(summary);
        BlogPostVO localDraft = blogMcpService.create(request, false);
        try {
            PublishResult result = juejinBlogSyncService.sync(userId, localDraft.getId(), juejinCategoryId, juejinTagIds);
            billingService.chargeFlat(userId, BillingBiz.JUEJIN_PUBLISH, "juejin:" + localDraft.getId(),
                    "post:" + localDraft.getId(), billingService.flatPoints(BillingBiz.JUEJIN_PUBLISH), "掘金发布成功");
            return result;
        } catch (RuntimeException exception) {
            throw new JuejinPublishException(localDraft.getId(), exception.getMessage());
        }
    }

    /** Syncs one owned local blog post to Juejin. */
    public PublishResult syncOwnedPost(Long postId, String juejinCategoryId, List<String> juejinTagIds) {
        Long userId = requireUserId();
        PublishResult result = juejinBlogSyncService.sync(userId, postId, juejinCategoryId, juejinTagIds);
        billingService.chargeFlat(userId, BillingBiz.JUEJIN_PUBLISH, "juejin:" + postId,
                "post:" + postId, billingService.flatPoints(BillingBiz.JUEJIN_PUBLISH), "掘金发布成功");
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

    /**
     * Raised when the Juejin publish fails after the local draft was created; carries the
     * local draft id so the caller can retry the sync safely.
     */
    public static class JuejinPublishException extends RuntimeException {
        private final Long localPostId;

        public JuejinPublishException(Long localPostId, String message) {
            super(message);
            this.localPostId = localPostId;
        }

        public Long localPostId() { return localPostId; }
    }

    /** Raised when the MCP caller has not completed DinQorAI authorization. */
    public static class McpAuthorizationRequiredException extends RuntimeException { }
}