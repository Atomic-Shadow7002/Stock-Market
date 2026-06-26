package com.luffy.trading.otp;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.luffy.trading.config.RateLimitService;
import com.luffy.trading.exception.ResourceNotFoundException;
import com.luffy.trading.response.ApiResponse;
import com.luffy.trading.user.User;
import com.luffy.trading.user.UserRepository;
import com.luffy.trading.util.SecurityUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Both endpoints require a valid Bearer access token (default SecurityConfig
 * rule: anyRequest().authenticated() already covers /otp/**, since it is not
 * under /auth/**). The token is issued at registration time, before phone
 * verification completes, so a freshly-registered user can call these
 * immediately even though login itself is blocked until verified.
 */
@RestController
@RequestMapping("/otp")
@RequiredArgsConstructor
public class OtpController {

    private final OtpService otpService;
    private final UserRepository userRepository;
    private final RateLimitService rateLimitService;

    @PostMapping("/send")
    public ResponseEntity<ApiResponse<Void>> send(
            @Valid @RequestBody SendOtpRequest request,
            HttpServletRequest httpRequest) {

        rateLimitService.checkOtpSendPerIp(clientIp(httpRequest));

        User user = currentUser();
        otpService.generateAndSend(user, request.type());

        return ResponseEntity.ok(ApiResponse.success("OTP sent", null));
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Void>> verify(@Valid @RequestBody VerifyOtpRequest request) {
        User user = currentUser();
        otpService.verify(user, request.type(), request.code());
        return ResponseEntity.ok(ApiResponse.success(request.type() + " verified successfully", null));
    }

    private User currentUser() {
        return userRepository.findById(SecurityUtils.currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
