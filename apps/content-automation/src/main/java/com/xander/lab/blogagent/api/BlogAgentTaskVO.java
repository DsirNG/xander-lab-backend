package com.xander.lab.blogagent.api;

import com.xander.lab.blogagent.domain.BlogAgentMessage;
import com.xander.lab.blogagent.domain.BlogAgentSource;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.blogagent.domain.BlogAgentVersion;
import com.xander.lab.content.api.MediaAssetRef;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** HTTP detail projection for a user-owned BlogAgent task. */
@Data
public class BlogAgentTaskVO {
    private BlogAgentTask task;
    private List<String> tags;
    private Map<String, Object> contentBoundary;
    private Map<String, Object> knowledgeGraph;
    private List<BlogAgentSource> sources;
    private List<BlogAgentVersion> versions;
    private List<MediaAssetRef> illustrations;
    private List<BlogAgentMessage> messages;
}
