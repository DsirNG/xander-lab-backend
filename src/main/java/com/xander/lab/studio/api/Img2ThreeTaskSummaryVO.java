package com.xander.lab.studio.api;

import lombok.Data;

/** Lightweight task data for a user's generation history; excludes the potentially large spec and source. */
@Data
public class Img2ThreeTaskSummaryVO {
    private Long id;
    private String status;
    private String stage;
    private String title;
    private String referenceMediaUrl;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;
}
