CREATE TABLE object_upload_ticket (
  id CHAR(36) PRIMARY KEY,
  purpose VARCHAR(40) NOT NULL,
  owner_id BIGINT NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(128),
  expected_size BIGINT NOT NULL,
  created_by BIGINT NOT NULL,
  expires_at DATETIME NOT NULL,
  consumed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_object_upload_ticket_key (object_key),
  KEY idx_object_upload_ticket_owner (purpose, owner_id, created_at),
  KEY idx_object_upload_ticket_expiry (expires_at, consumed_at),
  CONSTRAINT fk_object_upload_ticket_creator FOREIGN KEY(created_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
