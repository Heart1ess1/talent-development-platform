-- Run only in a disposable database initialized with DEMO_USERS_ENABLED=true.
INSERT INTO service_station(name) VALUES('请假验收站A'),('请假验收站B') ON DUPLICATE KEY UPDATE name=VALUES(name);
INSERT INTO sys_user(username,display_name,password_hash,role,must_change_password)
SELECT 'leave_employee','请假验收员工',password_hash,'EMPLOYEE',false FROM sys_user WHERE username='admin'
ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash),must_change_password=false;
INSERT INTO sys_user(username,display_name,password_hash,role,must_change_password)
SELECT 'leave_station','验收站长',password_hash,'STATION_MANAGER',false FROM sys_user WHERE username='admin'
ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash),must_change_password=false;
INSERT INTO sys_user(username,display_name,password_hash,role,must_change_password)
SELECT 'leave_area','验收区域经理',password_hash,'AREA_MANAGER',false FROM sys_user WHERE username='admin'
ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash),must_change_password=false;
INSERT INTO sys_user(username,display_name,password_hash,role,must_change_password)
SELECT 'leave_director','验收培训负责人',password_hash,'TRAINING_DIRECTOR',false FROM sys_user WHERE username='admin'
ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash),must_change_password=false;
INSERT INTO employee(user_id,employee_no,name,station_id)
SELECT u.id,'LEAVE-DEMO-001','请假验收员工',s.id FROM sys_user u JOIN service_station s ON s.name='请假验收站A' WHERE u.username='leave_employee'
ON DUPLICATE KEY UPDATE station_id=VALUES(station_id);
INSERT IGNORE INTO station_manager_scope(user_id,station_id)
SELECT u.id,s.id FROM sys_user u JOIN service_station s ON s.name='请假验收站A' WHERE u.username='leave_station';
INSERT IGNORE INTO area_manager_scope(user_id,station_id)
SELECT u.id,s.id FROM sys_user u JOIN service_station s ON s.name='请假验收站A' WHERE u.username='leave_area';
