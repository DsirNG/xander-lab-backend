package com.xander.lab.blogagent.api;

import com.xander.lab.blogagent.domain.BlogAgentSource;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.blogagent.domain.BlogAgentVersion;
import com.xander.lab.content.api.MediaAssetRef;
import com.xander.lab.blogagent.domain.BlogAgentMessage;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class BlogAgentTaskVO {
    private BlogAgentTask task;
    private List<String> tags;
    private Map<String, Object> contentBoundary;
    private Map<String, Object> knowledgeGraph;
    private List<BlogAgentSource> sources;
    private List<BlogAgentVersion> versions;
    /**
     * 跨域字段：用契约引用 {@link MediaAssetRef}，不用 {@code publishing} 的实体。
     *
     * <p>13 个字段与实体逐一同名，所以序列化后的 JSON 与改造前完全一致 —— 这次是纯重构。
     * 前端（AgentPreviewPanel）实际只读 id / url / originalName，收窄属于 §11 契约变更，另开一次。</p>
     */
    private List<MediaAssetRef> illustrations;
    private List<BlogAgentMessage> messages;
}
