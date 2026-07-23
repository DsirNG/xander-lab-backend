package com.xander.lab.dto.agent;

import com.xander.lab.entity.BlogAgentSource;
import com.xander.lab.entity.BlogAgentTask;
import com.xander.lab.entity.BlogAgentVersion;
import lombok.Data;

import java.util.List;

@Data
public class BlogAgentTaskVO {
    private BlogAgentTask task;
    private List<String> tags;
    private List<BlogAgentSource> sources;
    private List<BlogAgentVersion> versions;
}
