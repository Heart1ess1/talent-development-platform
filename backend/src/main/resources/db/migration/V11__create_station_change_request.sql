CREATE TABLE station_change_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  employee_id BIGINT NOT NULL,
  current_station_id BIGINT,
  requested_station_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  review_comment VARCHAR(255),
  reviewed_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_scr_employee FOREIGN KEY(employee_id) REFERENCES employee(id),
  CONSTRAINT fk_scr_req_station FOREIGN KEY(requested_station_id) REFERENCES service_station(id),
  CONSTRAINT fk_scr_reviewer FOREIGN KEY(reviewed_by) REFERENCES sys_user(id)
);
