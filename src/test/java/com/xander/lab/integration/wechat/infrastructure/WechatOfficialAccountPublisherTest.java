package com.xander.lab.integration.wechat.infrastructure;

import com.xander.lab.integration.wechat.WechatOfficialAccountProperties;
import com.xander.lab.integration.wechat.application.WechatOfficialAccountService;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WechatOfficialAccountPublisherTest {
    private WechatOfficialAccountProperties properties;
    private WechatOfficialAccountService accounts;
    private WechatOfficialAccountClient client;
    private WechatOfficialAccountPublisher publisher;

    @BeforeEach
    void setUp() {
        properties = new WechatOfficialAccountProperties();
        properties.setEnabled(true);
        accounts = mock(WechatOfficialAccountService.class);
        client = mock(WechatOfficialAccountClient.class);
        publisher = new WechatOfficialAccountPublisher(properties, accounts, client);
    }

    @Test
    void convertsMarkdownUploadsCoverAndCreatesDraft() {
        when(accounts.requireAccessToken(7L)).thenReturn("token");
        when(client.uploadPermanentImage("token", "https://cdn.example/cover.png"))
                .thenReturn("cover-media-id");
        when(client.createDraft(eq("token"), eq("标题"), eq(""), eq("摘要"),
                contains("<h1>正文</h1>"), eq("https://dinqor.cn/blog/12"), eq("cover-media-id")))
                .thenReturn("draft-media-id");

        PublishResult result = publisher.publish(new PublishCommand(
                -12L, 7L, "标题", "摘要", "# 正文\n\n正文内容", null,
                "https://cdn.example/cover.png", List.of("Java")));

        assertThat(result.platform()).isEqualTo("wechat");
        assertThat(result.externalId()).isEqualTo("draft-media-id");
        verify(client).uploadPermanentImage("token", "https://cdn.example/cover.png");
        verify(client).createDraft(eq("token"), eq("标题"), eq(""), eq("摘要"),
                contains("正文内容"), eq("https://dinqor.cn/blog/12"), eq("cover-media-id"));
    }

    @Test
    void requiresCoverBeforeCallingWeChat() {
        when(accounts.requireAccessToken(7L)).thenReturn("token");

        assertThatThrownBy(() -> publisher.publish(new PublishCommand(
                -12L, 7L, "标题", "摘要", "正文", null, "", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("微信公众号同步需要先为文章设置封面图");

        verifyNoInteractions(client);
    }
}
