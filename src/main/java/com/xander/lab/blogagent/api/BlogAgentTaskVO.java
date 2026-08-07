package com.xander.lab.blogagent.api;

import com.xander.lab.blogagent.domain.BlogAgentSource;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.blogagent.domain.BlogAgentVersion;
import com.xander.lab.entity.BlogMediaAsset;
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
    private List<BlogMediaAsset> illustrations;
    private List<BlogAgentMessage> messages;
}
