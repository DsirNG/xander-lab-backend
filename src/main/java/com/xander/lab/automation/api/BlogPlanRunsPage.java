package com.xander.lab.automation.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Paginated run history for one plan. */
@Data
@Builder
public class BlogPlanRunsPage {
    private List<BlogPlanRunVO> records;
    private long total;
    private long page;
    private long size;
}