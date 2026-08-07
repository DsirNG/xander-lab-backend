package com.xander.lab.publishing.domain;

import java.util.List;

public record PublishCommand(Long taskId, Long userId, String title, String summary,
                             String content, String categoryId, String coverImage, List<String> tags) {
    public PublishCommand { tags = tags == null ? List.of() : List.copyOf(tags); }
}
