package com.xander.lab.dto;

import lombok.Data;

import java.util.List;

@Data
public class ComponentDetailVO {
    private Long id;
    private String title;
    private String desc;
    private String tag;
    private String iconKey;
    private String author;
    private String version;
    private String sourceCode;
    private String libraryCode;
    private String wrapperCode;
    private String cssCode;
    private List<ComponentDetailPageVO> detailPages;
    private List<ComponentScenarioVO> scenarios;
}
