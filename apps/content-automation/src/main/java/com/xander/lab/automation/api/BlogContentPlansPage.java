package com.xander.lab.automation.api;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Paginated content plans for the current user. */
@Data
@Builder
public class BlogContentPlansPage {

    private List<BlogContentPlanVO> records;
    private long total;
    private long page;
    private long size;
}
