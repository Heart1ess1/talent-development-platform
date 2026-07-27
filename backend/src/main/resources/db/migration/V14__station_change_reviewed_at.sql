ALTER TABLE station_change_request
  ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by;

UPDATE station_change_request
SET reviewed_at = updated_at
WHERE status IN ('APPROVED', 'REJECTED') AND reviewed_at IS NULL;

CREATE INDEX idx_scr_employee_reviewed
  ON station_change_request(employee_id, status, reviewed_at);
