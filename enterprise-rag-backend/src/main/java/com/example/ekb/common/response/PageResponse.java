package com.example.ekb.common.response;

import java.util.List;

import com.baomidou.mybatisplus.core.metadata.IPage;

public record PageResponse<T>(
        List<T> records,
        long page,
        long size,
        long total
) {

    public static <T> PageResponse<T> from(IPage<T> page) {
        return new PageResponse<>(
                page.getRecords(),
                page.getCurrent(),
                page.getSize(),
                page.getTotal()
        );
    }

    public static <T> PageResponse<T> of(List<T> records, long page, long size, long total) {
        return new PageResponse<>(records, page, size, total);
    }
}
