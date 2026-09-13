package com.jk.hotlist.service.impl;

import com.jk.counter.service.CounterService;
import com.jk.hotlist.api.dto.HotItemResponse;
import com.jk.hotlist.api.dto.HotListResponse;
import com.jk.hotlist.config.HotListProperties;
import com.jk.hotlist.service.HotListService;
import com.jk.knowpost.mapper.KnowPostMapper;
import com.jk.knowpost.model.KnowPostFeedRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.*;

@Service
public class HotListServiceImpl implements HotListService {

    private static final Logger log = LoggerFactory.getLogger(HotListServiceImpl.class);
    private static final String RANK_DAILY = "hot:rank:daily:%s";
    private static final String RANK_WEEKLY = "hot:rank:weekly:%d:w%02d";
    private static final String RANK_ALLTIME = "hot:rank:alltime";
    private static final String VIEW_COUNT = "hot:vc:%d";
    private static final String VIEW_DEDUP = "hot:vd:%d:%d";

    private final StringRedisTemplate redis;
    private final CounterService counterService;
    private final KnowPostMapper knowPostMapper;
    private final HotListProperties prop;

    public HotListServiceImpl(StringRedisTemplate redis,
                              CounterService counterService,
                              KnowPostMapper knowPostMapper,
                              HotListProperties prop) {
        this.redis = redis;
        this.counterService = counterService;
        this.knowPostMapper = knowPostMapper;
        this.prop = prop;
    }

    // ======================== 浏览计数 ========================

    @Override
    public void recordView(long postId, long userId) {
        String dedupKey = String.format(VIEW_DEDUP, postId, userId);
        Boolean isNew = redis.opsForValue()
                .setIfAbsent(dedupKey, "1", Duration.ofHours(prop.getViewDedup().getHours()));
        if (Boolean.TRUE.equals(isNew)) {
            redis.opsForValue().increment(String.format(VIEW_COUNT, postId));
        }
    }

    @Override
    public long getViewCount(long postId) {
        String val = redis.opsForValue().get(String.format(VIEW_COUNT, postId));
        return val == null ? 0 : Long.parseLong(val);
    }

    // ======================== 查询接口 ========================

    @Override
    public HotListResponse getDaily(int page, int size) {
        String key = String.format(RANK_DAILY, LocalDate.now());
        return queryRank("daily", key, page, size);
    }

    @Override
    public HotListResponse getWeekly(int page, int size) {
        LocalDate now = LocalDate.now();
        WeekFields wf = WeekFields.of(Locale.getDefault());
        int week = now.get(wf.weekOfWeekBasedYear());
        String key = String.format(RANK_WEEKLY, now.getYear(), week);
        return queryRank("weekly", key, page, size);
    }

    @Override
    public HotListResponse getAlltime(int page, int size) {
        return queryRank("alltime", RANK_ALLTIME, page, size);
    }

    private HotListResponse queryRank(String window, String key, int page, int size) {
        int start = (page - 1) * size;
        int end = start + size - 1;

        // reverseRange 返回 LinkedHashSet，保持分数降序
        Set<String> idSet = redis.opsForZSet().reverseRange(key, start, end);
        if (idSet == null || idSet.isEmpty()) {
            return new HotListResponse(window, page, size, List.of(), false);
        }

        List<String> orderedIds = new ArrayList<>(idSet);
        List<Long> postIds = orderedIds.stream().map(Long::valueOf).toList();

        // 批量取 Feed 行、分数、计数
        List<KnowPostFeedRow> rows = knowPostMapper.listFeedRowsByIds(postIds);
        Map<Long, KnowPostFeedRow> rowMap = new HashMap<>();
        for (KnowPostFeedRow r : rows) rowMap.put(r.getId(), r);

        Map<String, Map<String, Long>> countsBatch = counterService.getCountsBatch(
                "knowpost", orderedIds, List.of("like", "fav", "view", "comment"));

        // 按 ZSet 顺序组装结果
        List<HotItemResponse> items = new ArrayList<>();
        for (String sid : orderedIds) {
            Long pid = Long.valueOf(sid);
            KnowPostFeedRow row = rowMap.get(pid);
            if (row == null) continue;

            Double score = redis.opsForZSet().score(key, sid);
            Map<String, Long> counts = countsBatch.getOrDefault(sid, Map.of());

            HotItemResponse item = new HotItemResponse();
            item.setId(sid);
            item.setTitle(row.getTitle());
            item.setDescription(row.getDescription());
            item.setAuthorId(String.valueOf(row.getCreatorId()));
            item.setAuthorNickname(row.getAuthorNickname());
            item.setAuthorAvatar(row.getAuthorAvatar());
            item.setLikeCount(counts.getOrDefault("like", 0L));
            item.setFavCount(counts.getOrDefault("fav", 0L));
            item.setViewCount(getViewCount(pid));
            item.setCommentCount(counts.getOrDefault("comment", 0L));
            item.setHeatScore(score != null ? score : 0.0);
            item.setPublishTime(row.getPublishTime());
            items.add(item);
        }

        Long total = redis.opsForZSet().zCard(key);
        boolean hasMore = total != null && start + size < total;

        return new HotListResponse(window, page, size, items, hasMore);
    }

    // ======================== 定时重算 ========================

    @Scheduled(fixedRateString = "${hotlist.recalc-interval-seconds:300}000")
    public void recalculate() {
        log.debug("hot.recalc start");
        Instant now = Instant.now();

        String dailyKey = String.format(RANK_DAILY, LocalDate.now());
        rebuildWindow(dailyKey, now, 24, prop.getDailyMaxSize());

        LocalDate today = LocalDate.now();
        WeekFields wf = WeekFields.of(Locale.getDefault());
        int week = today.get(wf.weekOfWeekBasedYear());
        String weeklyKey = String.format(RANK_WEEKLY, today.getYear(), week);
        rebuildWindow(weeklyKey, now, 168, prop.getWeeklyMaxSize());

        rebuildWindow(RANK_ALLTIME, now, Integer.MAX_VALUE, prop.getAlltimeMaxSize());

        log.debug("hot.recalc done");
    }

    private void rebuildWindow(String zsetKey, Instant now, int windowHours, int maxSize) {
        Set<String> currentIds = redis.opsForZSet().reverseRange(zsetKey, 0, -1);

        // 已有成员 + 最新发布的公开帖子合并，确保新帖也能进榜
        Set<String> idSet = new LinkedHashSet<>();
        if (currentIds != null) {
            idSet.addAll(currentIds);
        }
        List<KnowPostFeedRow> recentRows = knowPostMapper.listFeedPublic(maxSize, 0);
        for (KnowPostFeedRow r : recentRows) {
            idSet.add(String.valueOf(r.getId()));
        }
        if (idSet.isEmpty()) return;
        List<String> idList = new ArrayList<>(idSet);
        log.info("hot.recalc: {} posts for {}", idList.size(), zsetKey);

        // 批量获取 Feed 行
        List<Long> postIds = idList.stream().map(Long::valueOf).toList();
        List<KnowPostFeedRow> rows = knowPostMapper.listFeedRowsByIds(postIds);
        Map<Long, KnowPostFeedRow> rowMap = new HashMap<>();
        for (KnowPostFeedRow r : rows) rowMap.put(r.getId(), r);

        // 批量获取计数
        Map<String, Map<String, Long>> countsBatch = counterService.getCountsBatch(
                "knowpost", idList, List.of("like", "fav", "view", "comment"));

        // 计算分数并更新 ZSet
        for (String sid : idList) {
            long pid = Long.parseLong(sid);
            KnowPostFeedRow row = rowMap.get(pid);
            if (row == null || row.getPublishTime() == null) continue;

            long ageHours = Duration.between(row.getPublishTime(), now).toHours();
            if (ageHours > windowHours && windowHours < Integer.MAX_VALUE) {
                // 内容超出窗口，从 ZSet 移除
                redis.opsForZSet().remove(zsetKey, sid);
                continue;
            }

            Map<String, Long> counts = countsBatch.getOrDefault(sid, Map.of());
            double score = calcScore(
                    counts.getOrDefault("like", 0L),
                    counts.getOrDefault("fav", 0L),
                    getViewCount(pid),
                    counts.getOrDefault("comment", 0L),
                    ageHours);

            redis.opsForZSet().add(zsetKey, sid, score);
        }

        // 裁剪超出部分
        redis.opsForZSet().removeRange(zsetKey, 0, -(maxSize + 1));

        if (windowHours < Integer.MAX_VALUE) {
            redis.expire(zsetKey, Duration.ofHours(windowHours + 24));
        }
    }

    // ======================== 热度公式 ========================

    double calcScore(long likes, long favs, long views, long comments, long ageHours) {
        double engagement = likes * prop.getWeight().getLike()
                + favs * prop.getWeight().getFav()
                + views * prop.getWeight().getView()
                + comments * prop.getWeight().getComment();

        double denominator = Math.pow(ageHours + prop.getDecay().getBufferHours(),
                prop.getDecay().getGravity());
        double score = engagement / denominator * 1000.0;

        // 新内容加成：发布 2 小时内乘 1.5
        if (ageHours < 2) {
            score *= 1.5;
        }

        return score;
    }
}
