package com.jk.knowpost.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jk.common.util.OutboxMessageUtil;
import com.jk.relation.outbox.OutboxTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Feed 推流 Kafka 消费者。
 * 监听 Canal→Kafka 的 outbox 消息，在知文发布时将 postId 推入所有粉丝的时间线 ZSet。
 */
@Service
public class FeedFanoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(FeedFanoutConsumer.class);

    private final ObjectMapper objectMapper;
    private final FeedFanoutService fanoutService;
    private final TagIndexService tagIndexService;

    public FeedFanoutConsumer(ObjectMapper objectMapper, FeedFanoutService fanoutService, TagIndexService tagIndexService) {
        this.objectMapper = objectMapper;
        this.fanoutService = fanoutService;
        this.tagIndexService = tagIndexService;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "feed-fanout-consumer")
    public void onMessage(String message, Acknowledgment ack) {
        try {
            List<JsonNode> rows = OutboxMessageUtil.extractRows(objectMapper, message);
            if (rows.isEmpty()) {
                ack.acknowledge();
                return;
            }

            for (JsonNode row : rows) {
                JsonNode payloadNode = row.get("payload");
                if (payloadNode == null) {
                    continue;
                }

                JsonNode payload = objectMapper.readTree(payloadNode.asText());
                String entity = text(payload.get("entity"));
                String op = text(payload.get("op"));
                Long postId = asLong(payload.get("id"));
                Long creatorId = asLong(payload.get("creatorId"));

                if (!"knowpost".equals(entity) || postId == null || creatorId == null) {
                    continue;
                }

                if (!"upsert".equalsIgnoreCase(op)) {
                    continue;
                }

                long publishTimeMs = System.currentTimeMillis();
                fanoutService.fanout(postId, creatorId, publishTimeMs);
                tagIndexService.indexTags(postId);
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.warn("feed.fanout consumer error: {}", e.getMessage());
            ack.acknowledge(); // 不重试，避免积压
        }
    }

    private String text(JsonNode n) {
        return n == null ? null : n.asText();
    }

    private Long asLong(JsonNode n) {
        if (n == null) return null;
        try {
            return Long.parseLong(n.asText());
        } catch (Exception e) {
            return null;
        }
    }
}
