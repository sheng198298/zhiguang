package com.jk.hotlist.service;

import com.jk.hotlist.api.dto.HotListResponse;

public interface HotListService {
    HotListResponse getDaily(int page, int size);

    HotListResponse getWeekly(int page, int size);

    HotListResponse getAlltime(int page, int size);

    void recordView(long postId, long userId);

    long getViewCount(long postId);
}
