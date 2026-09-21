package com.xander.lab.blogagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.content.api.ContentPostInput;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.BlogPrivateReadService;
import com.xander.lab.publishing.application.ContentAgentPostService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlogAgentPublishServiceTest {

    @Test
    void delegatesReadyTaskPublicationToContentOwner() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        BlogPrivateReadService privateReadService = mock(BlogPrivateReadService.class);
        ContentAgentPostService contentPostService = mock(ContentAgentPostService.class);
        BlogAgentTaskReadService taskReadService = mock(BlogAgentTaskReadService.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));

        BlogAgentTask task = new BlogAgentTask();
        task.setId(42L);
        task.setUserId(7L);
        task.setStatus("ready");
        task.setTitle("  title ");
        task.setSummary("summary");
        task.setContent("content");
        task.setCategoryId("career");
        task.setTagsJson("[\"java\",\"spring\"]");
        when(taskReadService.findOwnedTask(7L, 42L)).thenReturn(task);
        BlogPostVO post = new BlogPostVO();
        post.setId(88L);
        when(contentPostService.createBlog(eq(7L), eq(new ContentPostInput(
                "title", "summary", "content", "career", null, java.util.List.of("java", "spring"))),
                eq(true), eq((String) null))).thenReturn(post);

        BlogAgentPublishService service = new BlogAgentPublishService(jdbcTemplate,
                transactionTemplate, new ObjectMapper(), privateReadService, contentPostService,
                taskReadService);

        BlogPostVO result = service.publish(7L, 42L);

        assertThat(result).isSameAs(post);
        verify(contentPostService).createBlog(eq(7L), eq(new ContentPostInput(
                "title", "summary", "content", "career", null, java.util.List.of("java", "spring"))),
                eq(true), eq((String) null));
        verify(jdbcTemplate).update(anyString(), eq(88L), eq(42L), eq(7L));
    }
}
