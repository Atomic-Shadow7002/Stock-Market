CREATE TABLE otp_verifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(10) NOT NULL CHECK (type IN ('PHONE', 'EMAIL')),
    code_hash VARCHAR(255) NOT NULL,
    -- BCrypt hash only, never plaintext
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_otp_user_type UNIQUE (user_id, type) -- enforces "one active OTP per user per type"
);
CREATE INDEX idx_otp_expires_at ON otp_verifications (expires_at);