package com.luffy.trading.config;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.luffy.trading.exception.RateLimitExceededException;

import io.github.bucket4j.Bucket;


@Service
public class RateLimitService {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** 5 registrations / hour / IP */
    public void checkRegistrationPerIp(String ip) {
        consume("register:" + ip, () -> Bucket.builder()
                .addLimit(limit -> limit.capacity(5).refillIntervally(5, Duration.ofHours(1)))
                .build());
    }

    /** 10 login attempts / 15 min / IP */
    public void checkLoginPerIp(String ip) {
        consume("login:" + ip, () -> Bucket.builder()
                .addLimit(limit -> limit.capacity(10).refillIntervally(10, Duration.ofMinutes(15)))
                .build());
    }

    /** 3 OTP sends / 15 min / user */
    public void checkOtpSendPerUser(UUID userId) {
        consume("otp-send-user:" + userId, () -> Bucket.builder()
                .addLimit(limit -> limit.capacity(3).refillIntervally(3, Duration.ofMinutes(15)))
                .build());
    }

    /** 5 OTP sends / hour / IP */
    public void checkOtpSendPerIp(String ip) {
        consume("otp-send-ip:" + ip, () -> Bucket.builder()
                .addLimit(limit -> limit.capacity(5).refillIntervally(5, Duration.ofHours(1)))
                .build());
    }

    private void consume(String key, Supplier<Bucket> bucketSupplier) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> bucketSupplier.get());
        if (!bucket.tryConsume(1)) {
            throw new RateLimitExceededException("Rate limit exceeded — please try again later");
        }
    }
}
