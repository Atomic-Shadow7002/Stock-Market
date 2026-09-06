package com.angelone.angelone_market_service.session;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import org.apache.commons.codec.binary.Base32;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.time.Instant;

/**
 * WHY this exists at all: AngelOne's login requires a live TOTP code, same as if you
 * were typing it from Google Authenticator. Since this is YOUR OWN account (not a
 * per-user login), the TOTP secret is just a static value you configure once — the
 * same base32 string your authenticator app was seeded with when you enabled 2FA
 * on the AngelOne SmartAPI portal. This class regenerates the current 6-digit code
 * from that secret so the daily re-login can run unattended.
 */
@Component
public class TotpGenerator {

    private final TimeBasedOneTimePasswordGenerator totp;

    public TotpGenerator() throws Exception {
        this.totp = new TimeBasedOneTimePasswordGenerator();
    }

    public String currentCode(String base32Secret) {
        try {
            byte[] decoded = new Base32().decode(base32Secret);
            Key key = new SecretKeySpec(decoded, "HmacSHA1");
            int code = totp.generateOneTimePassword(key, Instant.now());
            return String.format("%06d", code);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TOTP code — check ANGEL_TOTP_SECRET", e);
        }
    }
}
