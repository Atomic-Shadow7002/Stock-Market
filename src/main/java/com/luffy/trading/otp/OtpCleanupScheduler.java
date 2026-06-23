package com.luffy.trading.otp;

import java.time.OffsetDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Component
@RequiredArgsConstructor
public class OtpCleanupScheduler {

    private final OtpRepository otpRepository;

    @Scheduled(fixedRateString = "${otp.cleanup-interval-ms:600000}") // every 10 min by default
    @Transactional
    public void purgeExpired() {
        int deleted = otpRepository.deleteAllExpired(OffsetDateTime.now());
        if (deleted > 0) {
            log.info("OTP cleanup: removed {} expired OTP record(s)", deleted);
        }
    }
}
