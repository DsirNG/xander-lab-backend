package com.xander.lab.integration.mcp.csdn.application;

import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.ContentPostStatus;
import com.xander.lab.content.api.PublicationPort;
import com.xander.lab.content.api.PublishCommand;
import com.xander.lab.content.api.PublishResult;
import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.integration.api.CsdnBlogSyncPort;
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
 * {@link CsdnBlogSyncService} 作为 {@link CsdnBlogSyncPort} 实现的边界行为。
 *
 * <p>本类此前<b>没有自己的测试</b>（只被 {@code PublishPostToolTest} 间接覆盖）。
 * 它现在是一个契约端口的实现，所以三件事必须被钉住：</p>
 * <ol>
 *   <li><b>两份投影的换算。</b>{@code content.api.PublishResult} →
 *       {@code integration.api.BlogSyncResult} 是本类里唯一一处"等价 DTO 换算"，
 *       三个分量全是 {@code String}，位置写错编译器不会有任何意见。
 *       只有本类同时看得见两份类型，所以这条断言只能住在这里。</li>
 *   <li><b>幂等键的符号。</b>{@code taskId} 传的是 {@code -postId}：
 *       同一篇文章的"本站发布"与"同步到 CSDN"必须落在不同的幂等键上，
 *       写成 {@code +postId} 会让两次发布互相撞键，而返回值看起来完全正常。</li>
 *   <li><b>取的是 {@code categoryName} 而不是 {@code category}。</b>
 *       {@code ContentPost} 里这两个字段相邻、都是 {@code String}，
 *       写反只会让同步过去的分类变成一个英文标识。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class CsdnBlogSyncServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long POST_ID = 7L;

    @Mock private ContentPostPort contentPostPort;
    @Mock private PublicationPort publicationPort;

    private CsdnBlogSyncService newService() {
        return new CsdnBlogSyncService(contentPostPort, publicationPort);
    }

    /**
     * 两份投影在共享字段上必须逐字相等。
     *
     * <p>三个字面量互不相同，所以 {@code toContract} 里任何两个分量被调换都会让这条失败 ——
     * 这正是"契约不许依赖契约、各自带一份等价 DTO"的代价所在。</p>
     */
    @Test
    void syncKeepsEverySharedFieldWhenConvertingToTheContractResult() {
        PublishResult published = new PublishResult("csdn", "csdn-7", "https://blog.csdn.net/x/7");
        when(contentPostPort.findOwned(USER_ID, POST_ID)).thenReturn(post(POST_ID));
        when(publicationPort.publish(any(), eq("csdn"))).thenReturn(published);

        BlogSyncResult result = newService().sync(USER_ID, POST_ID);

        assertThat(result.platform()).isEqualTo(published.platform());
        assertThat(result.externalId()).isEqualTo(published.externalId());
        assertThat(result.url()).isEqualTo(published.url());
        assertThat(List.of(result.platform(), result.externalId(), result.url()))
                .doesNotHaveDuplicates();
    }

    @Test
    void syncUsesTheNegatedPostIdAndTheCsdnCategoryName() {
        when(contentPostPort.findOwned(USER_ID, POST_ID)).thenReturn(post(POST_ID));
        when(publicationPort.publish(any(), eq("csdn")))
                .thenReturn(new PublishResult("csdn", "1", "u"));

        newService().sync(USER_ID, POST_ID);

        ArgumentCaptor<PublishCommand> captor = ArgumentCaptor.forClass(PublishCommand.class);
        verify(publicationPort).publish(captor.capture(), eq("csdn"));
        PublishCommand command = captor.getValue();
        // 幂等键 = (taskId, platform)，站外同步用 -postId 与本站发布错开。
        assertThat(command.taskId()).isEqualTo(-POST_ID);
        assertThat(command.userId()).isEqualTo(USER_ID);
        // category 是 "backend"、categoryName 是 "后端"：同步用展示名。
        assertThat(command.categoryId()).isEqualTo("后端");
        assertThat(command.tags()).containsExactly("java");
    }

    @Test
    void syncRejectsAMissingPostWithoutTouchingThePlatform() {
        when(contentPostPort.findOwned(USER_ID, POST_ID)).thenReturn(null);

        assertThatThrownBy(() -> newService().sync(USER_ID, POST_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
        verify(publicationPort, never()).publish(any(), any());
    }

    /** 软删（{@code status == -1}）与"不存在"走同一条拒绝路径，重试没有意义。 */
    @Test
    void syncRejectsASoftDeletedPost() {
        when(contentPostPort.findOwned(USER_ID, POST_ID)).thenReturn(trashedPost(POST_ID));

        assertThatThrownBy(() -> newService().sync(USER_ID, POST_ID))
                .isInstanceOf(IllegalArgumentException.class);
        verify(publicationPort, never()).publish(any(), any());
    }

    /**
     * {@code postId} 为 {@code null} 时直接返回 {@code null}，且<b>不</b>联系内容域。
     *
     * <p>这条短路是必需的而不是优化：下面那句会算 {@code -postId}，对 {@code null} 拆箱会 NPE。</p>
     */
    @Test
    void findSucceededShortCircuitsOnANullPostId() {
        assertThat(newService().findSucceeded(USER_ID, null)).isNull();
        verify(publicationPort, never()).findSucceeded(any(), any(), any());
        verify(contentPostPort, never()).findOwned(any(), any());
    }

    @Test
    void findSucceededConvertsTheStoredRecordUsingTheNegatedPostId() {
        PublishResult stored = new PublishResult("csdn", "csdn-7", "https://blog.csdn.net/x/7");
        when(publicationPort.findSucceeded(USER_ID, -POST_ID, "csdn")).thenReturn(stored);

        BlogSyncResult result = newService().findSucceeded(USER_ID, POST_ID);

        assertThat(result.platform()).isEqualTo(stored.platform());
        assertThat(result.externalId()).isEqualTo(stored.externalId());
        assertThat(result.url()).isEqualTo(stored.url());
        verify(contentPostPort, never()).findOwned(any(), any());
    }

    /** 没有成功记录时契约用 {@code null} 表示，而不是空对象或空串。 */
    @Test
    void findSucceededReturnsNullWhenThereIsNoSuccessRecord() {
        when(publicationPort.findSucceeded(USER_ID, -POST_ID, "csdn")).thenReturn(null);

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
