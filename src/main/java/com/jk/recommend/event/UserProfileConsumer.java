package com.jk.recommend.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jk.counter.event.CounterTopics;
import com.jk.knowpost.mapper.KnowPostMapper;
import com.jk.knowpost.model.KnowPost;
import com.jk.recommend.config.RecommendProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 用户画像聚合消费者。
 * <p>监听点赞/收藏计数事件（{@link CounterTopics#EVENTS}），将用户行为折叠为兴趣标签画像与作者亲密度画像，
 * 供推荐排序使用。独立 consumer group，与计数聚合消费者互不影响。</p>
 */
@Slf4j
@Service
public class UserProfileConsumer {

    private final StringRedisTemplate redis;
    private final KnowPostMapper knowPostMapper;
    private final RecommendProperties prop;
    private final ObjectMapper objectMapper;

    public UserProfileConsumer(StringRedisTemplate redis,
                               KnowPostMapper knowPostMapper,
                               RecommendProperties prop,
                               ObjectMapper objectMapper) {
        this.redis = redis;
        this.knowPostMapper = knowPostMapper;
        this.prop = prop;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = CounterTopics.EVENTS, groupId = "user-profile-consumer")
    public void onMessage(String message, Acknowledgment ack) {
        try {
            JsonNode n = objectMapper.readTree(message);
            if (n == null) {
                ack.acknowledge();
                return;
            }
            String entityType = n.path("entityType").asText(null);
            String metric = n.path("metric").asText(null);
            if (!"knowpost".equals(entityType) || metric == null) {
                ack.acknowledge();
                return;
            }

            int weight = profileWeight(metric);
            if (weight == 0) {
                ack.acknowledge();
                return;
            }

            long userId = n.path("userId").asLong(0);
            int delta = n.path("delta").asInt(0);
            long postId = Long.parseLong(n.path("entityId").asText("0"));
            int signedWeight = delta >= 0 ? weight : -weight;

            accumulate(userId, postId, signedWeight);
            ack.acknowledge();
        } catch (Exception e) {
            log.warn("user-profile consumer error: {}", e.getMessage());
            ack.acknowledge(); // 画像聚合非关键路径，失败不重试避免积压
        }
    }

    private int profileWeight(String metric) {
        if ("like".equals(metric)) return prop.getProfileWeight().getLike();
        if ("fav".equals(metric)) return prop.getProfileWeight().getFav();
        return 0;
    }

    private void accumulate(long userId, long postId, int signedWeight) {
        KnowPost post = knowPostMapper.findById(postId);
        if (post == null || post.getCreatorId() == null || post.getTags() == null) return;

        List<String> tags = parseList(post.getTags());
        if (tags.isEmpty()) return;

        String interestKey = "interest:" + userId;
        String affinityKey = "affinity:" + userId;

        for (String tag : tags) {
            redis.opsForZSet().incrementScore(interestKey, tag, signedWeight);
        }
        redis.opsForZSet().incrementScore(affinityKey, String.valueOf(post.getCreatorId()), signedWeight);

        // 清理非正分与裁剪，防止画像无限膨胀
        redis.opsForZSet().removeRangeByScore(interestKey, Double.NEGATIVE_INFINITY, 0);
        redis.opsForZSet().removeRangeByScore(affinityKey, Double.NEGATIVE_INFINITY, 0);
        redis.opsForZSet().removeRange(interestKey, 0, -(prop.getInterestMaxTags() + 1));
        redis.opsForZSet().removeRange(affinityKey, 0, -(prop.getAffinityMaxAuthors() + 1));

        Duration ttl = Duration.ofDays(prop.getProfileTtlDays());
        redis.expire(interestKey, ttl);
        redis.expire(affinityKey, ttl);
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
