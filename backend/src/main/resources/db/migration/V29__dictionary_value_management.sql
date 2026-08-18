ALTER TABLE talent_batch
  ADD COLUMN sort_order INT NOT NULL DEFAULT 0 AFTER name;

ALTER TABLE business_unit
  ADD COLUMN sort_order INT NOT NULL DEFAULT 0 AFTER name;

ALTER TABLE service_station
  ADD COLUMN sort_order INT NOT NULL DEFAULT 0 AFTER name;

CREATE TABLE dictionary_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  type_code VARCHAR(32) NOT NULL,
  item_value VARCHAR(64) NOT NULL,
  label VARCHAR(64) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_dictionary_type_value(type_code, item_value),
  INDEX idx_dictionary_type_enabled_sort(type_code, enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO dictionary_item(type_code,item_value,label,sort_order)
VALUES
  ('EDUCATION','专科','专科',10),
  ('EDUCATION','本科','本科',20),
  ('EDUCATION','硕士','硕士',30),
  ('EDUCATION','博士','博士',40),
  ('POLITICAL_STATUS','群众','群众',10),
  ('POLITICAL_STATUS','共青团员','共青团员',20),
  ('POLITICAL_STATUS','中共预备党员','中共预备党员',30),
  ('POLITICAL_STATUS','中共党员','中共党员',40);

INSERT IGNORE INTO dictionary_item(type_code,item_value,label,sort_order)
SELECT 'EDUCATION',TRIM(education),TRIM(education),1000
FROM employee
WHERE education IS NOT NULL AND TRIM(education)<>'';

INSERT IGNORE INTO dictionary_item(type_code,item_value,label,sort_order)
SELECT 'POLITICAL_STATUS',TRIM(political_status),TRIM(political_status),1000
FROM employee
WHERE political_status IS NOT NULL AND TRIM(political_status)<>'';
