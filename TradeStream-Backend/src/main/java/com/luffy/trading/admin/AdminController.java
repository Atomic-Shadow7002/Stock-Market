package com.luffy.trading.admin;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.luffy.trading.response.ApiResponse;
import com.luffy.trading.user.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * WHAT: Admin-only user management — list/search, view any profile,
 * suspend/reinstate, promote to admin.
 * WHY @PreAuthorize on every method AND a /admin/** matcher in
 * SecurityConfig: two independent layers. If either is ever accidentally
 * removed during a refactor, the other still blocks non-admins — one typo
 * shouldn't be all that stands between a USER token and this controller.
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminUserService adminUserService;

    @GetMapping
    public ResponseEntity<ApiResponse<Page<UserResponse>>> listUsers(
            @RequestParam(required = false) String phone,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return ResponseEntity.ok(ApiResponse.success(adminUserService.listUsers(phone, pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getUser(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(adminUserService.getUser(id)));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<UserResponse>> setStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        UserResponse response = adminUserService.setEnabled(id, request.enabled());
        String message = request.enabled() ? "User enabled" : "User disabled";
        return ResponseEntity.ok(ApiResponse.success(message, response));
    }

    @PostMapping("/{id}/promote")
    public ResponseEntity<ApiResponse<UserResponse>> promote(@PathVariable UUID id) {
        UserResponse response = adminUserService.promoteToAdmin(id);
        return ResponseEntity.ok(ApiResponse.success("User promoted to ADMIN", response));
    }
}
