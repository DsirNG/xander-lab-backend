package com.xander.lab.dto;

import lombok.Data;
import java.util.List;

@Data
public class ComponentCategoryVO {
    private String id;
    private String name;
    private String desc;
    private List<ComponentListVO> components;
}
