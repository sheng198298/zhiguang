package com.jk.hotlist.api;

import com.jk.hotlist.api.dto.HotListResponse;
import com.jk.hotlist.service.HotListService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/hot")
public class HotListController {

    private final HotListService hotListService;

    public HotListController(HotListService hotListService) {
        this.hotListService = hotListService;
    }

    @GetMapping("/daily")
    public ResponseEntity<HotListResponse> daily(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(hotListService.getDaily(page, size));
    }

    @GetMapping("/weekly")
    public ResponseEntity<HotListResponse> weekly(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(hotListService.getWeekly(page, size));
    }

    @GetMapping("/alltime")
    public ResponseEntity<HotListResponse> alltime(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(hotListService.getAlltime(page, size));
    }
}
