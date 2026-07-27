ALTER TABLE employee
  ADD COLUMN political_status VARCHAR(32) AFTER native_place,
  ADD COLUMN hobbies VARCHAR(255) AFTER residence,
  ADD COLUMN speciality VARCHAR(255) AFTER hobbies,
  ADD COLUMN id_card VARCHAR(32) AFTER email;
