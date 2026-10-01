package com.xander.lab.integration.wechat.infrastructure;

import com.xander.lab.integration.wechat.WechatOfficialAccountProperties;
import com.xander.lab.integration.wechat.application.WechatOfficialAccountService;
import com.xander.lab.publishing.domain.BlogPublisher;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Saves a local article as a WeChat official-account draft for the owning user. */
@Component
@RequiredArgsConstructor
public class WechatOfficialAccountPublisher implements BlogPublisher {
    private final WechatOfficialAccountProperties properties;
    private final WechatOfficialAccountService accounts;
    private final WechatOfficialAccountClient client;

    @Override
    public String platform() {
        return "wechat";
    }

    @Override
    public PublishResult publish(PublishCommand command) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("微信公众号同步功能暂未启用");
        }
        String accessToken = accounts.requireAccessToken(command.userId());
        String coverImage = command.coverImage();
        if (coverImage == null || coverImage.isBlank()) {
            throw new IllegalArgumentException("微信公众号同步需要先为文章设置封面图");
        }
        String coverMediaId = client.uploadPermanentImage(accessToken, coverImage);
        String html = WechatMarkdownConverter.toHtml(command.content());
        String originalUrl = "https://dinqor.cn/blog/" + Math.abs(command.taskId());
        String draftMediaId = client.createDraft(accessToken,
                command.title(),
                "",
                command.summary(),
                html,
                originalUrl,
                coverMediaId);
        return new PublishResult(platform(), draftMediaId, "");
    }
}
