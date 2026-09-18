package com.xander.lab.integration.mcp.juejin.application;

import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.ContentPostStatus;
import com.xander.lab.content.api.PublicationPort;
import com.xander.lab.content.api.PublishCommand;
import com.xander.lab.content.api.PublishResult;
import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.integration.api.JuejinBlogSyncPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JuejinBlogSyncService} 作为 {@link JuejinBlogSyncPort} 实现的边界行为。
 *
 * <p>与 {@code CsdnBlogSyncServiceTest} 同构，额外钉住掘金特有的两件事：</p>
 * <ol>
 *   <li><b>标签回落。</b>没给掘金 tag id 时用文章自己的标签名（由发布器解析成 tag_id）；
 *       给了就用给的。这条回落是掘金独有的，CSDN 那条没有。</li>
 *   <li><b>分类直通。</b>{@code juejinCategoryId} 原样进 {@code PublishCommand.categoryId}
 *       —— 注意这里与 CSDN 不同：CSDN 传的是文章的 {@code categoryName}，
 *       掘金传的是调用方给的分类（为 {@code null} 时由发布器回落）。</li>
 * </ol>
 *
 * <p>⚠️ 跨域的两个消费方（{@code agent} / {@code automation}）传的都是 {@code (null, null)}，
 * 所以上面两条回落路径才是<b>生产上真正走的那条</b>；传真实值的路径由同域的
 * {@code JuejinBlogSyncController} 走，测试在这里覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class JuejinBlogSyncServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long POST_ID = 7L;

    @Mock private ContentPostPort contentPostPort;
    @Mock private PublicationPort publicationPort;

    private JuejinBlogSyncService newService() {
        return new JuejinBlogSyncService(contentPostPort, publicationPort);
    }

    @Test
    void syncKeepsEverySharedFieldWhenConvertingToTheContractResult() {
        PublishResult published = new PublishResult("juejin", "jj-7", "https://juejin.cn/post/7");
        when(contentPostPort.findOwned(USER_ID, POST_ID)).thenReturn(post(POST_ID));
        when(publicationPort.publish(any(), eq("juejin"))).thenReturn(published);

        BlogSyncResult result = newService().sync(USER_ID, POST_ID, null, null);

        assertThat(result.platform()).isEqualTo(published.platform());
        assertThat(result.externalId()).isEqualTo(published.externalId());
        assertThat(result.url()).isEqualTo(published.url());
        assertThat(List.of(result.platform(), result.externalId(), result.url()))
                .doesNotHaveDuplicates();
    }

    @Test
    void syncUsesTheNegatedPostIdAndTheJuejinPlatform() {
        when(contentPostPort.findOwned(USER_ID, POST_ID)).thenReturn(post(POST_ID));
        when(publicationPort.publish(any(), eq("juejin")))
                .thenReturn(new PublishResult("juejin", "1", "u"));

        newService().sync(USER_ID, POST_ID, "6809637767543259144", null);

        ArgumentCaptor<PublishCommand> captor = ArgumentCaptor.forClass(PublishCommand.class);
        verify(publicationPort).publish(captor.capture(), eq("juejin"));
        PublishCommand command = captor.getValue();
        assertThat(command.taskId()).isEqualTo(-POST_ID);
        assertThat(command.userId()).isEqualTo(USER_ID);
        assertThat(command.categoryId()).isEqualTo("6809637767543259144");
    }

    /**
     * 两个 {@code null} 就是跨域两个消费方实际传的形状：分类由发布器回落，
     * 标签用文章自己的标签名。
     */
    @Test
    void syncFallsBackToTheArticlesOwnTagsWhenNoTagIdsAreGiven() {
        when(contentPostPort.findOwned(USER_ID, POST_ID)).thenReturn(post(POST_ID));
        when(publicationPort.publish(any(), eq("juejin")))
                .thenReturn(new PublishResult("juejin", "1", "u"));

        newService().sync(USER_ID, POST_ID, null, null);

        ArgumentCaptor<PublishCommand> captor = ArgumentCaptor.forClass(PublishCommand.class);
        verify(publicationPort).publish(captor.capture(), eq("juejin"));
        assertThat(captor.getValue().tags()).containsExactly("java");
        assertThat(captor.getValue().categoryId()).isNull();
    }

    /** 空列表与 {@code null} 一样回落到文章的标签 —— "给了但是空的"不是"指定了空标签"。 */
    @Test
    void syncTreatsAnEmptyTagIdListAsNoTagIds() {
        when(contentPostPort.findOwned(USER_ID, POST_ID)).thenReturn(post(POST_ID));
        when(publicationPort.publish(any(), eq("juejin")))
                .thenReturn(new PublishResult("juejin", "1", "u"));

        newService().sync(USER_ID, POST_ID, null, List.of());

        ArgumentCaptor<PublishCommand> captor = ArgumentCaptor.forClass(PublishCommand.class);
        verify(publicationPort).publish(captor.capture(), eq("juejin"));
        assertThat(captor.getValue().tags()).containsExactly("java");
    }

    @Test
    void syncUsesTheGivenTagIdsWhenProvided() {
        when(contentPostPort.findOwned(USER_ID, POST_ID)).thenReturn(post(POST_ID));
        when(publicationPort.publish(any(), eq("juejin")))
                .thenReturn(new PublishResult("juejin", "1", "u"));

        newService().sync(USER_ID, POST_ID, null, List.of("6809640407484334093"));

        ArgumentCaptor<PublishCommand> captor = ArgumentCaptor.forClass(PublishCommand.class);
        verify(publicationPort).publish(captor.capture(), eq("juejin"));
        assertThat(captor.getValue().tags()).containsExactly("6809640407484334093");
    }

    @Test
    void syncRejectsAMissingPostWithoutTouchingThePlatform() {
        when(contentPostPort.findOwned(USER_ID, POST_ID)).thenReturn(null);

        assertThatThrownBy(() -> newService().sync(USER_ID, POST_ID, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
        verify(publicationPort, never()).publish(any(), any());
    }

    @Test
    void syncRejectsASoftDeletedPost() {
        when(contentPostPort.findOwned(USER_ID, POST_ID)).thenReturn(trashedPost(POST_ID));

        assertThatThrownBy(() -> newService().sync(USER_ID, POST_ID, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(publicationPort, never()).publish(any(), any());
    }

    @Test
    void findSucceededShortCircuitsOnANullPostId() {
        assertThat(newService().findSucceeded(USER_ID, null)).isNull();
        verify(publicationPort, never()).findSucceeded(any(), any(), any());
        verify(contentPostPort, never()).findOwned(any(), any());
    }

    @Test
    void findSucceededConvertsTheStoredRecordUsingTheNegatedPostId() {
        PublishResult stored = new PublishResult("juejin", "jj-7", "https://juejin.cn/post/7");
        when(publicationPort.findSucceeded(USER_ID, -POST_ID, "juejin")).thenReturn(stored);

        BlogSyncResult result = newService().findSucceeded(USER_ID, POST_ID);

        assertThat(result.platform()).isEqualTo(stored.platform());
        assertThat(result.externalId()).isEqualTo(stored.externalId());
        assertThat(result.url()).isEqualTo(stored.url());
        verify(contentPostPort, never()).findOwned(any(), any());
    }

    @Test
    void findSucceededReturnsNullWhenThereIsNoSuccessRecord() {
        when(publicationPort.findSucceeded(USER_ID, -POST_ID, "juejin")).thenReturn(null);

        assertThat(newService().findSucceeded(USER_ID, POST_ID)).isNull();
    }

    private static ContentPost post(Long id) {
        return new ContentPost(id, "标题", "摘要", "正文", "backend", "后端", null,
                List.of("java"), USER_ID, "XanderDING", LocalDate.of(2026, 8, 1), "3 min",
                null, 12, ContentPostStatus.PUBLISHED, false, false);
    }

    private static ContentPost trashedPost(Long id) {
        return new ContentPost(id, "标题", "摘要", "正文", "backend", "后端", null,
                List.of("java"), USER_ID, "XanderDING", LocalDate.of(2026, 8, 1), "3 min",
                null, 12, ContentPostStatus.TRASHED, false, false);
    }
}
