package com.luffy.trading.admin;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.luffy.trading.auth.AuthRepository;
import com.luffy.trading.exception.IllegalSelfActionException;
import com.luffy.trading.exception.ResourceNotFoundException;
import com.luffy.trading.user.Role;
import com.luffy.trading.user.User;
import com.luffy.trading.user.UserRepository;
import com.luffy.trading.user.UserResponse;
import com.luffy.trading.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

/**
 * WHAT: Everything an ADMIN can do to *someone else's* account.
 * WHY every method here takes a target userId: unlike UserService (which
 * only ever acts on "the caller"), these methods act on any account by id.
 * That's exactly the extra capability that needs the ROLE_ADMIN gate —
 * enforced at the controller via @PreAuthorize AND at SecurityConfig's
 * filter-chain level (/admin/** → hasRole("ADMIN")), so a bug in one layer
 * doesn't leave the door open.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final AuthRepository authRepository;

    /**
     * WHAT: Paginated list of all users, optionally filtered by a
     * (partial, case-insensitive) phone match.
     * WHY partial match: an admin "searching" by phone is more useful with
     * a contains-match than requiring the exact E.164 string — phone is
     * still guaranteed unique, so a full number narrows to exactly one row
     * anyway.
     */
    @Transactional(readOnly = true)
    public Page<UserResponse> listUsers(String phone, Pageable pageable) {
        Page<User> page = StringUtils.hasText(phone)
                ? userRepository.findByPhoneContainingIgnoreCase(phone, pageable)
                : userRepository.findAll(pageable);
        return page.map(UserResponse::from);
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(UUID userId) {
        return UserResponse.from(findUserOrThrow(userId));
    }

    /**
     * WHAT: Enable/disable (suspend) a user account.
     * WHY the self-guard: an admin disabling their own account would lock
     * them out with no one left to undo it, if they happened to be the
     * only admin. Cheap to prevent, expensive to recover from.
     * WHY refresh tokens are revoked on disable: JwtFilter already blocks a
     * disabled user's *access* tokens (it checks user.isEnabled() on every
     * request), but their still-valid refresh token could otherwise mint a
     * fresh access token via /auth/refresh — that path doesn't re-check
     * enabled status, so we cut the refresh tokens here too.
     */
    @Transactional
    public UserResponse setEnabled(UUID userId, boolean enabled) {
        if (userId.equals(SecurityUtils.currentUserId())) {
            throw new IllegalSelfActionException("You cannot change your own account status");
        }

        User user = findUserOrThrow(userId);
        user.setEnabled(enabled);
        userRepository.save(user);

        if (!enabled) {
            authRepository.deleteAllByUserId(user.getId());
        }

        return UserResponse.from(user);
    }

    /**
     * WHAT: Promotes a user to ADMIN. Idempotent — promoting an existing
     * admin again is a harmless no-op rather than an error.
     * WHY no demote endpoint (yet): out of scope per the current design —
     * only promotion was asked for. Demoting is a materially different,
     * higher-stakes operation (e.g. "don't let the last admin demote
     * themselves") that deserves its own explicit design pass later.
     */
    @Transactional
    public UserResponse promoteToAdmin(UUID userId) {
        User user = findUserOrThrow(userId);
        user.setRole(Role.ADMIN);
        userRepository.save(user);
        return UserResponse.from(user);
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
