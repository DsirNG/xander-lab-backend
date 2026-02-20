package com.xander.lab.dto;

import lombok.Data;

import java.util.List;

@Data
public class ComponentDetailVO {
    private String id;
    private String title;
    private String desc;
    private String tag;
    private String iconKey;
    private String author;
    private String version;
    private List<ComponentDetailPageVO> detailPages;
    private List<ComponentScenarioVO> scenarios;
}
