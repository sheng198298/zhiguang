package com.jk.recommend.service;

import com.jk.knowpost.api.dto.FeedPageResponse;

public interface RecommendService {
    /**
     * 获取当前用户的个性化推荐 Feed（按 EdgeRank 分数降序），游标分页。
     *
     * @param userId 当前用户 ID
     * @param size   每页数量
     * @param cursor 游标（上一页末条的 score），为空表示第一页
     * @return 带游标的分页响应
     */
    FeedPageResponse getRecommendFeed(long userId, int size, String cursor);
}
