package com.xander.lab.integration.mcp;

import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.xander.lab.common.PageData;
import com.xander.lab.publishing.api.BlogPostVO;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpJsonMapperFactory} 的回归测试。
 *
 * <p>守的是一个<b>默认关闭</b>的路径：{@code mcp.blog} 的 {@code list_posts} / {@code get_post}
 * 只有在 {@code MCP_BLOG_ENABLED=true} 时才会挂到 {@code /api/mcp} 上。
 * 默认关闭意味着这个失败在开发环境里永远不出现，只在有人打开开关时以
 * “协议层报错”的形态爆出来。</p>
 */
class McpJsonMapperFactoryTest {

    /** 与 {@code BlogMcpServerConfiguration#listPostsTool} 实际返回的 payload 同形。 */
    private static Map<String, Object> listPostsPayload() {
        BlogPostVO post = new BlogPostVO();
        post.setId(7L);
        post.setTitle("标题");
        post.setStatus(1);
        post.setTags(List.of("backend"));
        post.setDate(LocalDate.of(2026, 9, 17));

        PageData<BlogPostVO> page = new PageData<>();
        page.setRecords(List.of(post));
        page.setTotal(1);
        page.setCurrent(1);
        page.setSize(10);
        page.setPages(1);
        page.setHasMore(false);

        return Map.of("ok", true, "posts", page);
    }

    @Test
    @DisplayName("带 LocalDate 的工具返回值能被序列化，且日期是 ISO 字符串")
    void serializesDateBearingToolPayload() throws Exception {
        String json = McpJsonMapperFactory.jsonMapper().writeValueAsString(listPostsPayload());

        assertTrue(json.contains("\"ok\":true"), "payload 形状变了：" + json);
        assertTrue(json.contains("\"date\":\"2026-09-17\""),
                "日期没有序列化成 ISO 字符串。若这里是时间戳数组，说明 WRITE_DATES_AS_TIMESTAMPS "
                        + "又被打开了，前端与 MCP 客户端会看到两种不同的日期表示：" + json);
    }

    @Test
    @DisplayName("BlogPostVO 的 tagsRaw 依旧不出现在线上 JSON 里")
    void keepsTagsRawOffTheWire() throws Exception {
        BlogPostVO post = new BlogPostVO();
        post.setId(7L);
        post.setTagsRaw("backend,frontend");
        post.setTags(List.of("backend", "frontend"));

        PageData<BlogPostVO> page = new PageData<>();
        page.setRecords(List.of(post));

        String json = McpJsonMapperFactory.jsonMapper().writeValueAsString(Map.of("posts", page));

        assertFalse(json.contains("tagsRaw"),
                "tagsRaw 是 MyBatis 的 GROUP_CONCAT 承接字段，@JsonIgnore 掉了才对：" + json);
        assertTrue(json.contains("\"tags\":[\"backend\",\"frontend\"]"));
    }

    /**
     * 这个断言是<b>金丝雀</b>：它证明 {@link McpJsonMapperFactory} 存在是有原因的。
     *
     * <p>哪天 SDK 给默认 mapper 注册了 JSR-310，这里会红 —— 那时正确动作是
     * <b>删掉 {@link McpJsonMapperFactory} 并改回 {@code McpJsonDefaults.getMapper()}</b>，
     * 而不是把这条测试删掉。</p>
     */
    @Test
    @DisplayName("金丝雀：SDK 的默认 mapper 现在仍处理不了 LocalDate")
    void sdkDefaultMapperStillCannotSerializeDateBearingPayload() {
        assertThrows(InvalidDefinitionException.class,
                () -> McpJsonDefaults.getMapper().writeValueAsString(listPostsPayload()),
                "SDK 默认 mapper 已经能处理 LocalDate 了 —— 可以删掉 McpJsonMapperFactory，"
                        + "并把 4 个 MCP 服务端配置与 McpConnectionFactory 改回 McpJsonDefaults.getMapper()。");
    }
}
