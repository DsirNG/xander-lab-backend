package com.xander.lab.automation.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
