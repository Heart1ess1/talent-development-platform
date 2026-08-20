ALTER TABLE course_session
  ADD COLUMN session_title_id BIGINT NULL AFTER course_id,
  ADD COLUMN delivery_mode VARCHAR(16) NOT NULL DEFAULT 'OFFLINE' AFTER location,
  ADD COLUMN training_location_id BIGINT NULL AFTER delivery_mode,
  ADD COLUMN meeting_url VARCHAR(512) NULL AFTER training_location_id,
  ADD CONSTRAINT fk_course_session_title_dictionary
    FOREIGN KEY (session_title_id) REFERENCES dictionary_item(id),
  ADD CONSTRAINT fk_course_session_location_dictionary
    FOREIGN KEY (training_location_id) REFERENCES dictionary_item(id),
  ADD INDEX idx_course_session_title_dictionary (session_title_id),
  ADD INDEX idx_course_session_location_dictionary (training_location_id);

INSERT IGNORE INTO dictionary_item(type_code,item_value,label,sort_order)
SELECT
  'SESSION_NAME',
  CONCAT('LEGACY_', LEFT(SHA2(TRIM(title), 256), 50)),
  LEFT(TRIM(title), 64),
  1000
FROM course_session
WHERE title IS NOT NULL AND TRIM(title) <> '';

UPDATE course_session session
JOIN dictionary_item item
  ON item.type_code = 'SESSION_NAME'
 AND item.item_value = CONCAT('LEGACY_', LEFT(SHA2(TRIM(session.title), 256), 50))
SET session.session_title_id = item.id
WHERE session.session_title_id IS NULL;

INSERT IGNORE INTO dictionary_item(type_code,item_value,label,sort_order)
SELECT
  'TRAINING_LOCATION',
  CONCAT('LEGACY_', LEFT(SHA2(TRIM(location), 256), 50)),
  LEFT(TRIM(location), 64),
  1000
FROM course_session
WHERE location IS NOT NULL
  AND TRIM(location) <> ''
  AND LOWER(TRIM(location)) NOT REGEXP '^https?://';

UPDATE course_session session
JOIN dictionary_item item
  ON item.type_code = 'TRAINING_LOCATION'
 AND item.item_value = CONCAT('LEGACY_', LEFT(SHA2(TRIM(session.location), 256), 50))
SET session.training_location_id = item.id
WHERE session.training_location_id IS NULL
  AND session.location IS NOT NULL
  AND LOWER(TRIM(session.location)) NOT REGEXP '^https?://';

UPDATE course_session
SET delivery_mode = 'ONLINE',
    meeting_url = TRIM(location),
    location = NULL,
    training_location_id = NULL
WHERE location IS NOT NULL
  AND LOWER(TRIM(location)) REGEXP '^https?://';
