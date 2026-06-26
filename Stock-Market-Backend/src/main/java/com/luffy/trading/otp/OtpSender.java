package com.luffy.trading.otp;

import com.luffy.trading.user.User;

public interface OtpSender {

    void send(User user, OtpType type, String plainCode);
}
