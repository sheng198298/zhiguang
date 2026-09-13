package com.jk.recommend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推荐模块配置。对齐 hotlist 的 {@link com.tongji.hotlist.config.HotListProperties} 风格。
 */
@Component
@ConfigurationProperties(prefix = "recommend")
@Data
public class RecommendProperties {
    /** 亲密度系数（作者维度个性化） */
    private double alpha = 0.3;
    /** 兴趣系数（标签维度个性化） */
    private double beta = 0.5;

    /** 兴趣召回取的用户 top 标签数 */
    private int interestTopTags = 10;
    /** 单路召回条数（兴趣/热门） */
    private int recallHotSize = 50;
    /** 社交召回条数上限 */
    private int recallSocialSize = 200;
    /** 推荐榜容量 */
    private int candidateMax = 500;
    /** 推荐榜 ZSet TTL（分钟） */
    private int zsetTtlMinutes = 45;

    /** interest ZSet 裁剪保留的最大标签数 */
    private int interestMaxTags = 50;
    /** affinity ZSet 裁剪保留的最大作者数 */
    private int affinityMaxAuthors = 200;
    /** 画像 ZSet TTL（天） */
    private int profileTtlDays = 90;

    private ProfileWeight profileWeight = new ProfileWeight();
    private HeatWeight heatWeight = new HeatWeight();
    private Decay decay = new Decay();

    /** 画像行为权重（兴趣/亲密度聚合，收藏权重大于点赞） */
    @Data
    public static class ProfileWeight {
        private int like = 3;
        private int fav = 5;
    }

    /** 热度公式权重（对齐 hotlist 的 weight 配置） */
    @Data
    public static class HeatWeight {
        private int like = 3;
        private int fav = 4;
        private int view = 1;
        private int comment = 5;
    }

    @Data
    public static class Decay {
        private double gravity = 1.5;
        private int bufferHours = 2;
    }
}
