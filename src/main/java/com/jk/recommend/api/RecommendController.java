package com.jk.recommend.api;

import com.jk.auth.token.JwtService;
import com.jk.knowpost.api.dto.FeedPageResponse;
import com.jk.recommend.service.RecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个性化推荐 Feed 接口。与现有 Feed 接口同前缀（/api/v1/knowposts）。
 */
@RestController
@RequestMapping("/api/v1/knowposts")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendService recommendService;
    private final JwtService jwtService;

    /**
     * 个性化推荐 Feed（EdgeRank 分数降序），游标分页；默认每页 20，最大 50。
     * 需要认证。
     */
    @GetMapping("/recommend")
    public FeedPageResponse recommend(@RequestParam(value = "cursor", required = false) String cursor,
                                      @RequestParam(value = "size", defaultValue = "20") int size,
                                      @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return recommendService.getRecommendFeed(userId, size, cursor);
    }
}
