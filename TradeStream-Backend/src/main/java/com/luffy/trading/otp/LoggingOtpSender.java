package com.luffy.trading.otp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.luffy.trading.user.User;

import lombok.extern.slf4j.Slf4j;


@Slf4j
@Component
@ConditionalOnProperty(name = "otp.sender", havingValue = "logging", matchIfMissing = true)
public class LoggingOtpSender implements OtpSender {

    @Override
    public void send(User user, OtpType type, String plainCode) {
        log.warn(
            "[DEV-ONLY OTP — NOT FOR PRODUCTION] type={} userId={} destination={} code={}",
            type,
            user.getId(),
            type == OtpType.PHONE ? user.getPhone() : user.getEmail(),
            plainCode
        );
    }
}
