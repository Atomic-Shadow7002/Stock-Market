ALTER TABLE otp_verifications
ALTER COLUMN type TYPE VARCHAR(20);
ALTER TABLE otp_verifications DROP CONSTRAINT IF EXISTS otp_verifications_type_check;
ALTER TABLE otp_verifications
ADD CONSTRAINT otp_verifications_type_check CHECK (type IN ('PHONE', 'EMAIL', 'PASSWORD_RESET'));