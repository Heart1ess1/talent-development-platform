INSERT INTO business_unit(name, enabled)
VALUES ('机动车', TRUE), ('城轨', TRUE)
ON DUPLICATE KEY UPDATE enabled = TRUE;

UPDATE employee e
JOIN service_station s
  ON s.id = e.station_id
JOIN business_unit bu
  ON bu.name = s.name
SET e.business_unit_id = COALESCE(e.business_unit_id, bu.id),
    e.station_id = NULL
WHERE s.name IN ('机动车', '城轨');

UPDATE service_station
SET enabled = FALSE
WHERE name IN ('机动车', '城轨');
