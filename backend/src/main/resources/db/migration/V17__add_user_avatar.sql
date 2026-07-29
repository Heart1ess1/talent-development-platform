ALTER TABLE sys_user
  ADD COLUMN avatar_storage_key VARCHAR(512) NULL AFTER display_name,
  ADD COLUMN avatar_content_type VARCHAR(64) NULL AFTER avatar_storage_key,
  ADD COLUMN avatar_size BIGINT NULL AFTER avatar_content_type,
  ADD COLUMN avatar_token VARCHAR(36) NULL AFTER avatar_size,
  ADD COLUMN avatar_updated_at DATETIME NULL AFTER avatar_token,
  ADD UNIQUE KEY uk_sys_user_avatar_token (avatar_token);
