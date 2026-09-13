package com.jk.knowpost.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * 关注推流核心服务。
 * 发布知文时，将 postId 写入所有粉丝的 feed:following:{followerId} ZSet。
 */
@Service
public class FeedFanoutService {

    private static final Logger log = LoggerFactory.getLogger(FeedFanoutService.class);
    private static final int MAX_FEED_SIZE = 800;
    private static final Duration FEED_TTL = Duration.ofSeconds(604800); // 7 days
    private static final int BATCH_SIZE = 500;
    private static final int MAX_FANOUT_FANS = 5000;

    private final StringRedisTemplate redis;

    public FeedFanoutService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 将知文推送到作者所有粉丝的时间线。
     *
     * @param postId        知文 ID
     * @param creatorId     作者用户 ID
     * @param publishTimeMs 发布时间（毫秒时间戳）
     */
    public void fanout(long postId, long creatorId, long publishTimeMs) {
        String fansKey = "uf:fans:" + creatorId;

        Long fanCount = redis.opsForZSet().zCard(fansKey);
        if (fanCount == null || fanCount == 0) {
            return;
        }
        if (fanCount > MAX_FANOUT_FANS) {
            log.info("feed.fanout skipped: creator={} fans={} exceeds threshold", creatorId, fanCount);
            return;
        }

        double score = (double) publishTimeMs;
        String member = String.valueOf(postId);

        int offset = 0;
        while (offset < fanCount) {
            Set<String> fanBatch = redis.opsForZSet().reverseRange(fansKey, offset, offset + BATCH_SIZE - 1);
            offset += BATCH_SIZE;
            if (fanBatch == null || fanBatch.isEmpty()) {
                break;
            }

            redis.executePipelined(new SessionCallback<Object>() {
                @Override
                public <K, V> Object execute(RedisOperations<K, V> ops) {
                    for (String fanId : fanBatch) {
                        String zKey = "feed:following:" + fanId;
                        ops.opsForZSet().add((K) zKey, (V) member, score);
                        ops.opsForZSet().removeRange((K) zKey, 0, -(MAX_FEED_SIZE + 1));
                        ops.expire((K) zKey, FEED_TTL);
                    }
                    return null;
                }
            });
        }

        log.info("feed.fanout done: post={} creator={} fans={}", postId, creatorId, fanCount);
    }
}
