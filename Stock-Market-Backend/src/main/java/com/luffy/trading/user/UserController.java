package com.luffy.trading.user;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.luffy.trading.response.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> me() {
        return ResponseEntity.ok(ApiResponse.success(userService.getOwnProfile()));
    }

    @PutMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> updateMe(
            @Valid @RequestBody UpdateProfileRequest request) {
        UserResponse response = userService.updateOwnProfile(request);
        return ResponseEntity.ok(ApiResponse.success("Profile updated", response));
    }

    @PostMapping("/me/password/otp")
    public ResponseEntity<ApiResponse<Void>> requestPasswordChangeOtp(HttpServletRequest httpRequest) {
        userService.requestPasswordChangeOtp(clientIp(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("OTP sent to your registered phone", null));
    }

    @PutMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(request);
        return ResponseEntity.ok(ApiResponse.success(
                "Password changed — please log in again on other devices", null));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
