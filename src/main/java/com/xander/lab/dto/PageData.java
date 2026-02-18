package com.xander.lab.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一分页返回体
 * 简化 MyBatis-Plus IPage，只保留前端需要的字段
 */
@Data
@NoArgsConstructor
public class PageData<T> {

    /** 数据列表 */
    private List<T> records;

    /** 总记录数 */
    private long total;

    /** 当前页码 */
    private long current;

    /** 总页数 */
    private long pages;

    /** 每页大小 */
    private long size;

    /** 是否有更多数据 */
    private boolean hasMore;

    public PageData(IPage<T> page) {
        this.records = page.getRecords();
        this.total = page.getTotal();
        this.current = page.getCurrent();
        this.pages = page.getPages();
        this.size = page.getSize();
        this.hasMore = this.current < this.pages;
    }

    public static <T> PageData<T> of(IPage<T> page) {
        return new PageData<>(page);
    }
}
