package com.jk.knowpost.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 首页 Feed 分页响应。
 * page 用于基于页码的分页；cursor 用于基于游标的分页（如关注 Feed），互斥使用。
 */
public record FeedPageResponse(
        List<FeedItemResponse> items,
        int page,
        int size,
        boolean hasMore,
        @JsonInclude(JsonInclude.Include.NON_NULL) String cursor
) {
    public FeedPageResponse(List<FeedItemResponse> items, int page, int size, boolean hasMore) {
        this(items, page, size, hasMore, null);
    }
}