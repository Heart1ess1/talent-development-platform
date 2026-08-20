ALTER TABLE exam_attempt
  ADD COLUMN proctor_mode VARCHAR(32) NOT NULL DEFAULT 'FULLSCREEN_STRICT' AFTER deadline_at,
  ADD COLUMN fullscreen_capable BOOLEAN NOT NULL DEFAULT TRUE AFTER proctor_mode,
  ADD COLUMN client_context VARCHAR(255) NULL AFTER fullscreen_capable;
