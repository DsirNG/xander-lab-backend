package com.xander.lab.publishing.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Public publishing response shape owned by content-automation.
 *
 * <p>This deliberately mirrors the legacy HTTP shape. It is a service-local DTO, not a
 * dependency on core-host's publishing package.</p>
 */
@Data
public class BlogPostVO {

    private Long id;
    private String title;
    private String summary;
    private String content;
    private String category;
    private String categoryName;
    private String coverImage;
    private List<String> tags;

    @JsonIgnore
    private String tagsRaw;

    private Long userId;
    private String author;
    private LocalDate date;
    private String readTime;
    private String tips;
    private Integer views;
    private Integer status;
    private Boolean csdnSynced;
    private Boolean juejinSynced;

    public void parseTags() {
        if (tagsRaw == null || tagsRaw.isBlank()) {
            tags = Collections.emptyList();
            return;
        }
        tags = Arrays.asList(tagsRaw.split(","));
    }
}
