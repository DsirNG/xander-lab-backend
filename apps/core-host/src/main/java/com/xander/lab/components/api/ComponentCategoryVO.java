package com.xander.lab.components.api;

import lombok.Data;
import java.util.List;

@Data
public class ComponentCategoryVO {
    private String id;
    private String name;
    private String desc;
    private List<ComponentListVO> components;
}
