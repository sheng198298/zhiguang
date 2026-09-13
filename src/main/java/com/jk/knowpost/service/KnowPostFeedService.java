package com.jk.knowpost.service;

import com.jk.knowpost.api.dto.FeedPageResponse;

/**
 * 知文 Feed 业务接口。
 */
public interface KnowPostFeedService {
    FeedPageResponse getPublicFeed(int page, int size, Long currentUserIdNullable);

    FeedPageResponse getMyPublished(long userId, int page, int size);

    /**
     * 获取当前用户的关注 Feed（基于推流的个性化时间线）。
     *
     * @param userId 当前用户 ID
     * @param size   每页数量
     * @param cursor 游标（上一页最后一条的 score，毫秒时间戳），为空表示第一页
     * @return 带游标的分页响应
     */
    FeedPageResponse getFollowingFeed(long userId, int size, String cursor);
}