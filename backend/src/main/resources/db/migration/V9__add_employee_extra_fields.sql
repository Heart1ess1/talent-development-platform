ALTER TABLE employee
    ADD COLUMN political_status VARCHAR(32)  AFTER native_place,
  ADD COLUMN hobbies          VARCHAR(255) AFTER residence,
  ADD COLUMN speciality       VARCHAR(255) AFTER hobbies,
  ADD COLUMN id_card          VARCHAR(32)  AFTER email,
  ADD COLUMN department       VARCHAR(64)  AFTER station_id,
  ADD COLUMN tech_mentor      VARCHAR(64)  AFTER mentor_user_id,
  ADD COLUMN skill_mentor     VARCHAR(64)  AFTER tech_mentor,
  ADD COLUMN regional_rep     VARCHAR(64)  AFTER skill_mentor,
  ADD COLUMN station_head     VARCHAR(64)  AFTER regional_rep,
  ADD COLUMN leave_date       DATE         AFTER onboard_date,
  ADD COLUMN leave_notes      VARCHAR(255) AFTER leave_date,
  ADD COLUMN days_on_site     INT          AFTER leave_notes;