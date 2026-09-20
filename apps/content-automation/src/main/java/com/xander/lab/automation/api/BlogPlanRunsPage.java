package com.xander.lab.automation.api;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Paginated execution history for one content plan. */
@Data
@Builder
public class BlogPlanRunsPage {

    private List<BlogPlanRunVO> records;
    private long total;
    private long page;
    private long size;
}
