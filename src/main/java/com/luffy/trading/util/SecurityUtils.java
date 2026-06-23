package com.luffy.trading.util;

import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;

import com.luffy.trading.exception.ResourceNotFoundException;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static UUID currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResourceNotFoundException("No authenticated user in context");
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Unable to resolve authenticated user id from token");
        }
    }
}
