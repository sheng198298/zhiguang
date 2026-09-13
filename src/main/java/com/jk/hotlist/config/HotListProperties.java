package com.jk.hotlist.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hotlist")
@Data
public class HotListProperties {
    private int dailyMaxSize = 100;
    private int weeklyMaxSize = 200;
    private int alltimeMaxSize = 500;
    private int recalcIntervalSeconds = 300;

    private Weight weight = new Weight();
    private Decay decay = new Decay();
    private ViewDedup viewDedup = new ViewDedup();

    @Data
    public static class Weight {
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

    @Data
    public static class ViewDedup {
        private int hours = 12;
    }
}
