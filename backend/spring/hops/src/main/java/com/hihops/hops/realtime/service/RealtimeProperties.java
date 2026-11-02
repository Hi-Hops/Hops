package com.hihops.hops.realtime.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "hops.realtime")
public record RealtimeProperties(@NotNull Duration heartbeat, @NotNull Duration timeout) {
    @AssertTrue(message = "heartbeat와 timeout은 양수이며 heartbeat는 timeout보다 짧아야 합니다")
    public boolean isTimingValid() {
        return heartbeat != null && timeout != null && heartbeat.toMillis() > 0
                && timeout.toMillis() > heartbeat.toMillis();
    }
}
