-- V53: 一账号多角色（角色切换方案）
-- 只新增表与回填行；不修改、不删除 sys_user 现有数据，也不改动 V1–V52。
-- sys_user.role 继续保留，作为“主角色镜像”（is_primary=1 的那条角色）。

CREATE TABLE sys_user_role (
  user_id BIGINT NOT NULL,
  role_code VARCHAR(32) NOT NULL,
  is_primary BOOLEAN NOT NULL DEFAULT FALSE,
  primary_slot TINYINT GENERATED ALWAYS AS (CASE WHEN is_primary THEN 1 ELSE NULL END) STORED,
  assigned_by BIGINT,
  assigned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, role_code),
  UNIQUE KEY uk_user_role_primary (user_id, primary_slot),
  KEY idx_user_role_code (role_code),
  CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
  CONSTRAINT fk_user_role_assigned_by FOREIGN KEY (assigned_by) REFERENCES sys_user(id) ON DELETE SET NULL
);

-- 回填：现有每个账号的 sys_user.role 作为其主角色。
INSERT INTO sys_user_role (user_id, role_code, is_primary, assigned_by, assigned_at)
SELECT id, role, TRUE, id, CURRENT_TIMESTAMP
FROM sys_user
WHERE role IS NOT NULL AND role <> '';
