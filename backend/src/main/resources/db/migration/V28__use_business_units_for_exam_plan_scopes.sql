CREATE TABLE exam_plan_target_business_unit (
  plan_id BIGINT NOT NULL,
  business_unit_id BIGINT NOT NULL,
  PRIMARY KEY(plan_id, business_unit_id),
  CONSTRAINT fk_eptbu_plan
    FOREIGN KEY(plan_id) REFERENCES exam_plan(id) ON DELETE CASCADE,
  CONSTRAINT fk_eptbu_business_unit
    FOREIGN KEY(business_unit_id) REFERENCES business_unit(id)
);

-- V10 temporarily stored “机动车/城轨” as service stations. V16 moved those
-- categories to business_unit; preserve matching historical plan scopes.
INSERT IGNORE INTO exam_plan_target_business_unit(plan_id, business_unit_id)
SELECT target.plan_id, unit.id
FROM exam_plan_target_station target
JOIN service_station station ON station.id = target.station_id
JOIN business_unit unit ON unit.name = station.name;
