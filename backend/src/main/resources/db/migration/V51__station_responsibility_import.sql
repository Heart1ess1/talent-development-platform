CREATE TABLE station_responsibility_import (
  id CHAR(36) PRIMARY KEY,
  created_by BIGINT NOT NULL,
  file_hash CHAR(64) NOT NULL,
  payload JSON NOT NULL,
  preview JSON NOT NULL,
  baseline_hash CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  result JSON NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at DATETIME NULL,
  INDEX idx_sri_cleanup(created_at),
  INDEX idx_sri_owner(created_by,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
