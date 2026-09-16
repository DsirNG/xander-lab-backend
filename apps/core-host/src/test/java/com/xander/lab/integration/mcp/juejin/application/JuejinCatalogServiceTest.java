package com.xander.lab.integration.mcp.juejin.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinCategory;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinCategoryMapper;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinClient;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinPlatformProperties;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinTag;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinTagMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 目录同步服务：拉取接口 → 幂等落库 → 名称匹配与默认值回退。 */
@ExtendWith(MockitoExtension.class)
class JuejinCatalogServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private JuejinClient client;
    @Mock private JuejinCategoryMapper categoryMapper;
    @Mock private JuejinTagMapper tagMapper;
    @Mock private TransactionTemplate transactionTemplate;

    private JuejinCatalogService service;

    @BeforeEach
    void setUp() {
        JuejinPlatformProperties properties = new JuejinPlatformProperties();
        service = new JuejinCatalogService(properties, client, categoryMapper, tagMapper, transactionTemplate);
        // 事务模板在测试里直接执行回调，行为等价于真实事务提交；部分用例不触发同步，
        // 故用 lenient 避免严格桩校验。
        lenient().doAnswer(invocation -> {
            Consumer<?> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private static JsonNode categoryResponse() throws Exception {
        return MAPPER.readTree("""
                {"err_no":0,"data":[
                  {"category_id":"6809637769959178254","category":{"category_id":"6809637769959178254","category_name":"后端","rank":1},
                   "hot_tags":[{"tag_id":"6809640408797167623","tag_name":"后端"}]},
                  {"category_id":"6809637767543259144","category":{"category_id":"6809637767543259144","category_name":"前端","rank":2},
                   "hot_tags":[{"tag_id":"6809640407484334093","tag_name":"前端"},{"tag_id":"6809640398105870343","tag_name":"JavaScript"}]}
                ]}""");
    }

    private static JsonNode tagListResponse(boolean hasMore, String cursor) throws Exception {
        return MAPPER.readTree("""
                {"err_no":0,"data":[
                  {"tag_id":"6809640407484334093","tag":{"tag_id":"6809640407484334093","tag_name":"前端"}},
                  {"tag_id":"6809640408797167623","tag":{"tag_id":"6809640408797167623","tag_name":"后端"}}
                ],"has_more":%s,"cursor":"%s"}""".formatted(hasMore, cursor));
    }

    @Test
    void syncPersistsCategoriesHotTagsAndPaginatedTags() throws Exception {
        when(client.queryCategories(any())).thenReturn(categoryResponse());
        when(client.queryTagList(any(), anyString(), anyInt())).thenReturn(
                tagListResponse(true, "100"), tagListResponse(false, "200"));

        service.sync(new JuejinClient.Credential("cookie=abc", "csrf", "2608", "uuid-1"));

        ArgumentCaptor<JuejinCategory> categoryCaptor = ArgumentCaptor.forClass(JuejinCategory.class);
        verify(categoryMapper, times(2)).upsert(categoryCaptor.capture());
        assertThat(categoryCaptor.getAllValues())
                .extracting(JuejinCategory::getCategoryName)
                .containsExactly("后端", "前端");
        ArgumentCaptor<JuejinTag> tagCaptor = ArgumentCaptor.forClass(JuejinTag.class);
        // hot_tags 3 条 + 全量分页两页各 2 条 = 7 条
        verify(tagMapper, times(7)).upsert(tagCaptor.capture());
        assertThat(tagCaptor.getAllValues()).hasSize(7);
        // hot_tags 带来源分类归属；分页标签无归属
        assertThat(tagCaptor.getAllValues().stream()
                .filter(tag -> tag.getCategoryId() != null))
                .extracting(JuejinTag::getCategoryId)
                .containsOnly(6809637769959178254L, 6809637767543259144L, 6809637767543259144L);
        verify(client, times(2)).queryTagList(any(), anyString(), anyInt());
    }

    @Test
    void ensureFreshSkipsSyncWhenCatalogIsFresh() {
        when(categoryMapper.selectLatestSyncAt()).thenReturn(LocalDateTime.now());
        when(tagMapper.selectLatestSyncAt()).thenReturn(LocalDateTime.now());

        service.ensureFresh(new JuejinClient.Credential("cookie=abc", "csrf", "2608", "uuid-1"), 42L);

        verify(client, never()).queryCategories(any());
        verify(client, never()).queryTagList(any(), anyString(), anyInt());
    }

    @Test
    void ensureFreshSwallowsSyncFailureAndKeepsOldCatalog() throws Exception {
        when(categoryMapper.selectLatestSyncAt()).thenReturn(null);
        when(client.queryCategories(any())).thenThrow(new JuejinClient.JuejinApiException(-1, "boom"));

        service.ensureFresh(new JuejinClient.Credential("cookie=abc", "csrf", "2608", "uuid-1"), 42L);

        verify(tagMapper, never()).upsert(any());
    }

    @Test
    void resolveCategoryIdMatchesByNameAndDefaultsOnMiss() {
        when(categoryMapper.selectOne(any())).thenAnswer(invocation -> {
            JuejinCategory category = new JuejinCategory();
            category.setCategoryId(6809637767543259144L);
            category.setCategoryName("前端");
            return category;
        });
        assertThat(service.resolveCategoryId("前端")).isEqualTo("6809637767543259144");
        assertThat(service.resolveCategoryId("6809635626879549454")).isEqualTo("6809635626879549454");
        // 未命中回默认前端分类
        when(categoryMapper.selectOne(any())).thenReturn(null);
        assertThat(service.resolveCategoryId("不存在的分类")).isEqualTo(JuejinCatalogService.DEFAULT_CATEGORY_ID);
        assertThat(service.resolveCategoryId("  ")).isEqualTo(JuejinCatalogService.DEFAULT_CATEGORY_ID);
        assertThat(service.resolveCategoryId(null)).isEqualTo(JuejinCatalogService.DEFAULT_CATEGORY_ID);
    }

    @Test
    void resolveTagIdMatchesByNameAndReturnsNullOnMiss() {
        when(tagMapper.selectOne(any())).thenAnswer(invocation -> {
            JuejinTag tag = new JuejinTag();
            tag.setTagId(6809640448827588622L);
            tag.setTagName("Python");
            return tag;
        });
        assertThat(service.resolveTagId("Python")).isEqualTo("6809640448827588622");
        when(tagMapper.selectOne(any())).thenReturn(null);
        assertThat(service.resolveTagId("不存在的标签")).isNull();
        assertThat(service.resolveTagId(null)).isNull();
        assertThat(service.resolveTagId(" ")).isNull();
    }

    @Test
    void defaultsMatchHARCategoryAndTagSnapshot() {
        assertThat(JuejinCatalogService.DEFAULT_CATEGORY_ID).isEqualTo("6809637767543259144");
        assertThat(JuejinCatalogService.DEFAULT_TAG_ID).isEqualTo("6809640407484334093");
    }
}