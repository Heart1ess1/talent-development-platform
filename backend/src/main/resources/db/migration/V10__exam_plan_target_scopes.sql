INSERT INTO service_station(name,enabled)
VALUES ('机动车',TRUE),('城轨',TRUE)
ON DUPLICATE KEY UPDATE enabled=VALUES(enabled);

CREATE TABLE exam_plan_target_batch (
  plan_id BIGINT NOT NULL,
  batch_id BIGINT NOT NULL,
  PRIMARY KEY(plan_id,batch_id),
  CONSTRAINT fk_eptb_plan FOREIGN KEY(plan_id) REFERENCES exam_plan(id) ON DELETE CASCADE,
  CONSTRAINT fk_eptb_batch FOREIGN KEY(batch_id) REFERENCES talent_batch(id)
);

CREATE TABLE exam_plan_target_station (
  plan_id BIGINT NOT NULL,
  station_id BIGINT NOT NULL,
  PRIMARY KEY(plan_id,station_id),
  CONSTRAINT fk_epts_plan FOREIGN KEY(plan_id) REFERENCES exam_plan(id) ON DELETE CASCADE,
  CONSTRAINT fk_epts_station FOREIGN KEY(station_id) REFERENCES service_station(id)
);
