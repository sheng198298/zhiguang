package com.jk.hotlist.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class HotListResponse {
    private String window;
    private int page;
    private int size;
    private List<HotItemResponse> items;
    private boolean hasMore;

    public HotListResponse(String window, int page, int size, List<HotItemResponse> items, boolean hasMore) {
        this.window = window;
        this.page = page;
        this.size = size;
        this.items = items;
        this.hasMore = hasMore;
    }
}
