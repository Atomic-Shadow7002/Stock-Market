package com.luffy.trading.admin;

import jakarta.validation.constraints.NotNull;

/**
 * WHAT: Body for PATCH /admin/users/{id}/status.
 * WHY a dedicated request type instead of just a raw boolean path/query
 * param: keeps the endpoint self-documenting and consistent with every
 * other request in this codebase, and leaves room to add a reason/note
 * field later (e.g. "why was this account suspended") without breaking
 * the API shape.
 */
public record UpdateUserStatusRequest(
    @NotNull(message = "enabled is required")
    Boolean enabled
) {}
