package com.xander.lab.studio.api;

import lombok.Data;

@Data
public class Img2ThreeTaskVO {
    private Long id;
    private String status;
    private String stage;
    private String title;
    private String referenceMediaUrl;
    private Object analysis;
    private Object sceneSpec;
    private String factoryCode;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;
}
