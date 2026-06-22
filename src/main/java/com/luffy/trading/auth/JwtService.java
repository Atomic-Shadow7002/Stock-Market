package com.luffy.trading.auth;

import java.security.Key;
import java.util.Date;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.luffy.trading.exception.JwtValidationException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    private final JwtProperties jwtProperties;
    private final Key signingKey;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.signingKey = resolveKey(jwtProperties.getSecret());
    }

    /**
     * WHAT: Issues a short-lived access token, signed with HS512.
     * WHY: sub claim = userId (UUID string), never phone/email — keeps the
     *      token's identity stable even if the user changes those fields later.
     *      HS512 (not HS256) is used here to match the 512-bit key size —
     *      see resolveKey() note below for why that pairing matters.
     */
    public String generateAccessToken(UUID userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtProperties.getAccessTokenExpiry() * 1000);

        return Jwts.builder()
                .setSubject(userId.toString())
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(signingKey, SignatureAlgorithm.HS512)
                .compact();
    }

    /**
     * WHAT: Extracts userId from a valid token's subject claim.
     * WHY: Called by JwtFilter on every protected request to identify the caller.
     */
    public UUID extractUserId(String token) {
        Claims claims = parseClaims(token);
        return UUID.fromString(claims.getSubject());
    }

    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
        .verifyWith((javax.crypto.SecretKey) signingKey)
        .build()
        .parseSignedClaims(token)
        .getPayload();
        } catch (ExpiredJwtException e) {
            throw new JwtValidationException("Token has expired", e);
        } catch (Exception e) {
            throw new JwtValidationException("Invalid JWT token", e);
        }
    }

    /**
     * WHAT: Builds the HMAC signing key from the configured secret.
     * WHY: HS512 requires a key of at least 512 bits (64 bytes) per RFC 7518 —
     *      a shorter key with HS512 is a misconfiguration the JJWT library
     *      will reject outright at startup (WeakKeyException), which is the
     *      correct fail-fast behavior rather than silently using a weak key.
     */
    private Key resolveKey(String secret) {
        byte[] decoded = Decoders.BASE64.decode(secret);
        return Keys.hmacShaKeyFor(decoded);
    }
}