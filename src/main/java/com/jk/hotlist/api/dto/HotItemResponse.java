package com.jk.hotlist.api.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class HotItemResponse {
    private String id;
    private String title;
    private String description;
    private String authorId;
    private String authorNickname;
    private String authorAvatar;
    private long likeCount;
    private long favCount;
    private long viewCount;
    private long commentCount;
    private double heatScore;
    private Instant publishTime;
}
