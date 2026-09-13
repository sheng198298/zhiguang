package com.jk.knowpost.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jk.knowpost.mapper.KnowPostMapper;
import com.jk.knowpost.model.KnowPost;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 标签倒排索引：tag:posts:{tag} ZSet，member=postId，score=发布时间。
 * 供推荐模块的兴趣召回使用。
 */
@Slf4j
@Service
public class TagIndexService {

    private static final String TAG_POSTS_KEY = "tag:posts:%s";
    private static final Duration TTL = Duration.ofDays(90);

    private final StringRedisTemplate redis;
    private final KnowPostMapper knowPostMapper;
    private final ObjectMapper objectMapper;

    public TagIndexService(StringRedisTemplate redis, KnowPostMapper knowPostMapper, ObjectMapper objectMapper) {
        this.redis = redis;
        this.knowPostMapper = knowPostMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 将已发布知文写入其各标签的倒排索引。
     *
     * @param postId 知文 ID
     */
    public void indexTags(long postId) {
        KnowPost post = knowPostMapper.findById(postId);
        if (post == null || post.getTags() == null || post.getPublishTime() == null) return;

        List<String> tags = parseList(post.getTags());
        if (tags.isEmpty()) return;

        double score = (double) post.getPublishTime().toEpochMilli();
        String member = String.valueOf(postId);
        for (String tag : tags) {
            String key = String.format(TAG_POSTS_KEY, tag);
            redis.opsForZSet().add(key, member, score);
            redis.expire(key, TTL);
        }
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
