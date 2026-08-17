package com.xander.lab.integration.mcp.juejin.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinCategory;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinCategoryMapper;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinClient;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinPlatformProperties;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinTag;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinTagMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 维护本地的掘金分类/标签目录（juejin_category / juejin_tag）：按需调用 tag_api
 * 全量同步并幂等落库，发布时把本地分类名/标签名解析为掘金侧 ID。
 * 目录是全员共享的全站静态数据，不区分用户；同步仅由发布链路触发（过期或为空时），
 * 网络调用在事务外完成，落库走独立事务。名称未命中时回落到默认分类/标签。
 */
@Service
@Slf4j
public class JuejinCatalogService {

    /** 默认分类：前端（query_category_list 快照）。 */
    public static final String DEFAULT_CATEGORY_ID = "6809637767543259144";
    /** 默认标签：前端（query_tag_list 推荐首位）。 */
    public static final String DEFAULT_TAG_ID = "6809640407484334093";

    private static final int TAG_PAGE_SIZE = 100;
    private static final int MAX_TAG_PAGES = 10;

    private final JuejinPlatformProperties properties;
    private final JuejinClient client;
    private final JuejinCategoryMapper categoryMapper;
    private final JuejinTagMapper tagMapper;
    private final TransactionTemplate transactionTemplate;

    public JuejinCatalogService(JuejinPlatformProperties properties, JuejinClient client,
                                JuejinCategoryMapper categoryMapper, JuejinTagMapper tagMapper,
                                TransactionTemplate transactionTemplate) {
        this.properties = properties;
        this.client = client;
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 目录为空或超出新鲜度窗口时触发全量同步（分类 + hot_tags + 全量标签分页）。
     * 同步失败只打 WARN 不抛出：发布继续走既有目录数据，命中不了再回落默认值。
     */
    public void ensureFresh(JuejinClient.Credential credential, Long userId) {
        if (isFresh()) return;
        try {
            sync(credential);
            log.info("[Juejin] 分类/标签目录已同步（用户 {}）", userId);
        } catch (Exception ex) {
            log.warn("[Juejin] 分类/标签目录同步失败（用户 {}）：{}", userId, ex.getMessage());
        }
    }

    /** 分类目录与标签目录都已同步且未超过新鲜度窗口时视为可复用。 */
    private boolean isFresh() {
        LocalDateTime categorySync = categoryMapper.selectLatestSyncAt();
        LocalDateTime tagSync = tagMapper.selectLatestSyncAt();
        if (categorySync == null || tagSync == null) return false;
        LocalDateTime cutoff = LocalDateTime.now().minusHours(properties.getCatalogFreshnessHours());
        return categorySync.isAfter(cutoff) && tagSync.isAfter(cutoff);
    }

    /** 拉取全部接口数据并幂等落库；远程调用在事务外，落库在单独事务内批量 upsert。 */
    public void sync(JuejinClient.Credential credential) {
        List<JuejinCategory> categories = new ArrayList<>();
        List<JuejinTag> tags = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        JsonNode categoryResponse = client.queryCategories(credential);
        for (JsonNode item : categoryResponse.path("data")) {
            JsonNode category = item.path("category");
            if (category.isMissingNode() || category.isNull()) continue;
            JuejinCategory row = new JuejinCategory();
            row.setCategoryId(category.path("category_id").asLong());
            row.setCategoryName(category.path("category_name").asText(""));
            row.setRank(category.path("rank").asInt(0));
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            categories.add(row);
            for (JsonNode hot : item.path("hot_tags")) {
                JuejinTag tag = new JuejinTag();
                tag.setTagId(hot.path("tag_id").asLong());
                tag.setTagName(hot.path("tag_name").asText(""));
                tag.setCategoryId(row.getCategoryId());
                tag.setCreatedAt(now);
                tag.setUpdatedAt(now);
                tags.add(tag);
            }
        }
        // 全量标签分页（cursor 翻页，has_more 停止），页码封顶防止失控。
        String cursor = "0";
        for (int page = 0; page < MAX_TAG_PAGES; page++) {
            JsonNode response = client.queryTagList(credential, cursor, TAG_PAGE_SIZE);
            for (JsonNode item : response.path("data")) {
                JuejinTag tag = new JuejinTag();
                tag.setTagId(item.path("tag_id").asLong());
                tag.setTagName(item.path("tag").path("tag_name").asText(""));
                tag.setCreatedAt(now);
                tag.setUpdatedAt(now);
                tags.add(tag);
            }
            if (!response.path("has_more").asBoolean(false)) break;
            cursor = response.path("cursor").asText(cursor);
        }
        transactionTemplate.executeWithoutResult(status -> {
            for (JuejinCategory category : categories) categoryMapper.upsert(category);
            for (JuejinTag tag : tags) tagMapper.upsert(tag);
        });
    }

    /**
     * 解析分类：空值/未命中回落默认前端分类；纯数字按掘金 ID 直接透传；
     * 其余视为分类名按目录匹配。
     */
    public String resolveCategoryId(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) return DEFAULT_CATEGORY_ID;
        String value = nameOrId.strip();
        if (value.chars().allMatch(Character::isDigit)) return value;
        JuejinCategory category = categoryMapper.selectOne(
                new QueryWrapper<JuejinCategory>().eq("category_name", value).last("LIMIT 1"));
        return category == null ? DEFAULT_CATEGORY_ID : String.valueOf(category.getCategoryId());
    }

    /** 按标签名查掘金 ID；未命中返回 null（由调用方继续搜索或回落默认标签）。 */
    public String resolveTagId(String name) {
        if (name == null || name.isBlank()) return null;
        JuejinTag tag = tagMapper.selectOne(
                new QueryWrapper<JuejinTag>().eq("tag_name", name.strip()).last("LIMIT 1"));
        return tag == null ? null : String.valueOf(tag.getTagId());
    }
}