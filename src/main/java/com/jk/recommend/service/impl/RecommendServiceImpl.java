package com.jk.recommend.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jk.counter.service.CounterService;
import com.jk.knowpost.api.dto.FeedItemResponse;
import com.jk.knowpost.api.dto.FeedPageResponse;
import com.jk.knowpost.mapper.KnowPostMapper;
import com.jk.knowpost.model.KnowPostFeedRow;
import com.jk.recommend.config.RecommendProperties;
import com.jk.recommend.service.RecommendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 个性化推荐 Feed 实现。
 * <p>召回（多路）+ 排序（EdgeRank-lite 规则公式）+ 懒加载写 feed:recommend:{userId} ZSet。</p>
 * <p>排序公式：finalScore = baseHeat × (1 + α·log1p(affinity)) × (1 + β·log1p(interestMatch))。</p>
 */
@Slf4j
@Service
public class RecommendServiceImpl implements RecommendService {

    private static final String RECOMMEND_KEY = "feed:recommend:%d";
    private static final String FOLLOWING_KEY = "feed:following:%d";
    private static final String INTEREST_KEY = "interest:%d";
    private static final String AFFINITY_KEY = "affinity:%d";
    private static final String TAG_POSTS_KEY = "tag:posts:%s";
    private static final String HOT_ALLTIME_KEY = "hot:rank:alltime";
    private static final String VIEW_COUNT_KEY = "hot:vc:%d";

    private final StringRedisTemplate redis;
    private final CounterService counterService;
    private final KnowPostMapper knowPostMapper;
    private final RecommendProperties prop;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Object> singleFlight = new ConcurrentHashMap<>();

    public RecommendServiceImpl(StringRedisTemplate redis,
                                CounterService counterService,
                                KnowPostMapper knowPostMapper,
                                RecommendProperties prop,
                                ObjectMapper objectMapper) {
        this.redis = redis;
        this.counterService = counterService;
        this.knowPostMapper = knowPostMapper;
        this.prop = prop;
        this.objectMapper = objectMapper;
    }

    @Override
    public FeedPageResponse getRecommendFeed(long userId, int size, String cursor) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        String key = String.format(RECOMMEND_KEY, userId);

        // 懒加载：推荐榜缺失时重建（singleFlight 防击穿）
        if (Boolean.FALSE.equals(redis.hasKey(key))) {
            buildWithSingleFlight(userId, key);
        }

        double maxScore = Double.POSITIVE_INFINITY;
        if (cursor != null && !cursor.isEmpty()) {
            try {
                maxScore = Double.parseDouble(cursor);
            } catch (NumberFormatException ignored) {
            }
        }

        Set<ZSetOperations.TypedTuple<String>> raw =
                redis.opsForZSet().reverseRangeByScoreWithScores(key, Double.NEGATIVE_INFINITY, maxScore, 0, safeSize + 1);
        if (raw == null || raw.isEmpty()) {
            return new FeedPageResponse(List.of(), 0, safeSize, false, null);
        }

        List<ZSetOperations.TypedTuple<String>> entries = new ArrayList<>(raw);
        boolean hasMore = entries.size() > safeSize;
        if (hasMore) {
            entries = entries.subList(0, safeSize);
        }

        double lastScore = entries.get(entries.size() - 1).getScore();
        String nextCursor = hasMore ? String.valueOf((long) lastScore) : null;

        List<String> postIds = entries.stream().map(ZSetOperations.TypedTuple::getValue).toList();
        List<FeedItemResponse> items = buildItems(postIds, userId);

        return new FeedPageResponse(items, 0, safeSize, hasMore, nextCursor);
    }

    private void buildWithSingleFlight(long userId, String key) {
        Object lock = singleFlight.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            if (Boolean.FALSE.equals(redis.hasKey(key))) {
                buildRecommendZSet(userId);
            }
            singleFlight.remove(key);
        }
    }

    private void buildRecommendZSet(long userId) {
        Set<String> candidateIds = recall(userId);
        if (candidateIds.isEmpty()) return;

        List<Long> ids = candidateIds.stream().map(Long::valueOf).toList();
        List<KnowPostFeedRow> rows = knowPostMapper.listFeedRowsByIds(ids);
        if (rows.isEmpty()) return;

        Map<String, Map<String, Long>> countsBatch =
                counterService.getCountsBatch("knowpost", new ArrayList<>(candidateIds), List.of("like", "fav", "comment"));
        Map<String, Double> affinity = zsetScoreMap(String.format(AFFINITY_KEY, userId));
        Map<String, Double> interest = zsetScoreMap(String.format(INTEREST_KEY, userId));

        String key = String.format(RECOMMEND_KEY, userId);
        for (KnowPostFeedRow row : rows) {
            String sid = String.valueOf(row.getId());
            Map<String, Long> counts = countsBatch.getOrDefault(sid, Map.of());

            double baseHeat = heatScore(row, counts);
            double aff = affinity.getOrDefault(String.valueOf(row.getCreatorId()), 0.0);
            double interestMatch = interestMatch(row, interest);

            double score = baseHeat
                    * (1 + prop.getAlpha() * Math.log1p(aff))
                    * (1 + prop.getBeta() * Math.log1p(interestMatch));

            redis.opsForZSet().add(key, sid, score);
        }

        redis.opsForZSet().removeRange(key, 0, -(prop.getCandidateMax() + 1));
        redis.expire(key, Duration.ofMinutes(prop.getZsetTtlMinutes()));
    }

    private Set<String> recall(long userId) {
        Set<String> candidates = new LinkedHashSet<>();

        // 1. 社交召回：关注作者的内容
        Set<String> social = redis.opsForZSet().reverseRange(
                String.format(FOLLOWING_KEY, userId), 0, prop.getRecallSocialSize() - 1);
        if (social != null) candidates.addAll(social);

        // 2. 兴趣召回：用户 top 标签 → 标签倒排
        Set<String> topTags = redis.opsForZSet().reverseRange(
                String.format(INTEREST_KEY, userId), 0, prop.getInterestTopTags() - 1);
        if (topTags != null) {
            for (String tag : topTags) {
                Set<String> posts = redis.opsForZSet().reverseRange(
                        String.format(TAG_POSTS_KEY, tag), 0, prop.getRecallHotSize() - 1);
                if (posts != null) candidates.addAll(posts);
            }
        }

        // 3. 热门召回：全站总榜
        Set<String> hot = redis.opsForZSet().reverseRange(HOT_ALLTIME_KEY, 0, prop.getRecallHotSize() - 1);
        if (hot != null) candidates.addAll(hot);

        // 4. 兜底：最新公开内容（冷启动）
        if (candidates.size() < prop.getRecallHotSize()) {
            List<KnowPostFeedRow> recent = knowPostMapper.listFeedPublic(prop.getCandidateMax(), 0);
            for (KnowPostFeedRow r : recent) {
                candidates.add(String.valueOf(r.getId()));
            }
        }

        return candidates;
    }

    /**
     * 热度公式，对齐 HotListServiceImpl.calcScore：engagement / (ageHours+buffer)^gravity × 1000，新内容加成。
     */
    private double heatScore(KnowPostFeedRow row, Map<String, Long> counts) {
        long likes = counts.getOrDefault("like", 0L);
        long favs = counts.getOrDefault("fav", 0L);
        long comments = counts.getOrDefault("comment", 0L);
        long views = viewCount(row.getId());

        long ageHours = row.getPublishTime() == null ? 0
                : Duration.between(row.getPublishTime(), Instant.now()).toHours();

        double engagement = likes * prop.getHeatWeight().getLike()
                + favs * prop.getHeatWeight().getFav()
                + views * prop.getHeatWeight().getView()
                + comments * prop.getHeatWeight().getComment();

        double denominator = Math.pow(ageHours + prop.getDecay().getBufferHours(), prop.getDecay().getGravity());
        double score = engagement / denominator * 1000.0;
        if (ageHours < 2) {
            score *= 1.5;
        }
        return score;
    }

    private double interestMatch(KnowPostFeedRow row, Map<String, Double> interest) {
        double sum = 0.0;
        for (String tag : parseList(row.getTags())) {
            sum += interest.getOrDefault(tag, 0.0);
        }
        return sum;
    }

    private long viewCount(long postId) {
        String v = redis.opsForValue().get(String.format(VIEW_COUNT_KEY, postId));
        return v == null ? 0 : Long.parseLong(v);
    }

    private Map<String, Double> zsetScoreMap(String key) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeWithScores(key, 0, -1);
        Map<String, Double> map = new HashMap<>();
        if (tuples != null) {
            for (ZSetOperations.TypedTuple<String> t : tuples) {
                if (t.getValue() != null && t.getScore() != null) {
                    map.put(t.getValue(), t.getScore());
                }
            }
        }
        return map;
    }

    private List<FeedItemResponse> buildItems(List<String> postIds, long userId) {
        List<Long> ids = postIds.stream().map(Long::valueOf).toList();
        List<KnowPostFeedRow> rows = knowPostMapper.listFeedRowsByIds(ids);
        Map<Long, KnowPostFeedRow> rowMap = new HashMap<>();
        for (KnowPostFeedRow r : rows) {
            rowMap.put(r.getId(), r);
        }

        Map<String, Map<String, Long>> countsBatch =
                counterService.getCountsBatch("knowpost", postIds, List.of("like", "fav"));

        List<FeedItemResponse> items = new ArrayList<>(postIds.size());
        for (String pid : postIds) {
            KnowPostFeedRow row = rowMap.get(Long.valueOf(pid));
            if (row == null) continue;

            List<String> tags = parseList(row.getTags());
            List<String> imgs = parseList(row.getImgUrls());
            String cover = imgs.isEmpty() ? null : imgs.get(0);

            Map<String, Long> counts = countsBatch.getOrDefault(pid, Map.of());
            long likeCount = counts.getOrDefault("like", 0L);
            long favCount = counts.getOrDefault("fav", 0L);
            boolean liked = counterService.isLiked("knowpost", pid, userId);
            boolean faved = counterService.isFaved("knowpost", pid, userId);

            items.add(new FeedItemResponse(
                    pid,
                    row.getTitle(),
                    row.getDescription(),
                    cover,
                    tags,
                    row.getAuthorAvatar(),
                    row.getAuthorNickname(),
                    row.getAuthorTagJson(),
                    likeCount,
                    favCount,
                    liked,
                    faved,
                    row.getIsTop()
            ));
        }
        return items;
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
