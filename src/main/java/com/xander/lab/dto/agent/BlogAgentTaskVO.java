package com.xander.lab.dto.agent;

import com.xander.lab.entity.BlogAgentSource;
import com.xander.lab.entity.BlogAgentTask;
import com.xander.lab.entity.BlogAgentVersion;
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
}
