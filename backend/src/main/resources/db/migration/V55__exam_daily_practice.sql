-- V55: 考试中心「每日随机推题」（每日练习）基础表
-- 只新增 exam_daily_* 表，以及 question_bank 的抽题索引；不修改、不删除任何既有表与数据。
-- 时间仍按系统习惯存北京时间（Asia/Shanghai），DATETIME 不带时区，日期使用 DATE。

-- 1. 每日练习计划
CREATE TABLE exam_daily_plan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(500),
  bank_ids JSON NULL COMMENT '限定题库ID，空表示全部启用题库',
  question_types JSON NULL COMMENT '限定题型，默认单选/多选/判断',
  tags JSON NULL COMMENT '限定专业标签，空表示不限；公共题按现有规则参与',
  daily_count INT NOT NULL COMMENT '每日题数',
  score_per_question DECIMAL(5,2) NOT NULL COMMENT '每题分值',
  pass_score DECIMAL(5,2) NULL COMMENT '及格分，可空',
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  push_time TIME NOT NULL DEFAULT '00:00:00' COMMENT '每日推送时刻',
  deadline_time TIME NOT NULL DEFAULT '23:59:59' COMMENT '每日截止时刻',
  timezone VARCHAR(40) NOT NULL DEFAULT 'Asia/Shanghai',
  randomize_options BOOLEAN NOT NULL DEFAULT TRUE,
  repeat_policy VARCHAR(20) NOT NULL DEFAULT 'ALLOW_REPEAT'
    COMMENT 'ALLOW_REPEAT/REDUCE_COUNT/FAIL',
  include_weekends BOOLEAN NOT NULL DEFAULT TRUE,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
    COMMENT 'DRAFT/PUBLISHED/PAUSED/ENDED',
  published_at DATETIME NULL,
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_exam_daily_plan_name UNIQUE(name),
  INDEX idx_exam_daily_plan_status(status,start_date,end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. 计划参考范围
CREATE TABLE exam_daily_plan_target (
  plan_id BIGINT NOT NULL,
  target_type VARCHAR(20) NOT NULL
    COMMENT 'BATCH/BUSINESS_UNIT/CLASS/CLASS_POSITION/EMPLOYEE',
  target_id BIGINT NOT NULL,
  PRIMARY KEY(plan_id,target_type,target_id),
  CONSTRAINT fk_edpt_plan FOREIGN KEY(plan_id) REFERENCES exam_daily_plan(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. 参与人快照
CREATE TABLE exam_daily_participant (
  plan_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  left_at DATETIME NULL,
  PRIMARY KEY(plan_id,employee_id),
  CONSTRAINT fk_edpa_plan FOREIGN KEY(plan_id) REFERENCES exam_daily_plan(id) ON DELETE CASCADE,
  CONSTRAINT fk_edpa_employee FOREIGN KEY(employee_id) REFERENCES employee(id),
  INDEX idx_edpa_employee(employee_id,active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. 每人每天一套
CREATE TABLE exam_daily_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  practice_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING/IN_PROGRESS/SUBMITTED/EXPIRED',
  question_count INT NOT NULL,
  correct_count INT NULL,
  total_score DECIMAL(5,2) NULL,
  started_at DATETIME NULL,
  deadline_at DATETIME NOT NULL,
  submitted_at DATETIME NULL,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_eds_plan_employee_date(plan_id,employee_id,practice_date),
  CONSTRAINT fk_eds_plan FOREIGN KEY(plan_id) REFERENCES exam_daily_plan(id),
  CONSTRAINT fk_eds_employee FOREIGN KEY(employee_id) REFERENCES employee(id),
  INDEX idx_eds_employee_date(employee_id,practice_date),
  INDEX idx_eds_plan_date_status(plan_id,practice_date,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. 当日题目快照 + 去重唯一键
CREATE TABLE exam_daily_session_question (
  session_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  plan_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  score DECIMAL(5,2) NOT NULL,
  sort_order INT NOT NULL,
  repeated BOOLEAN NOT NULL DEFAULT FALSE COMMENT '题库不足时是否重复抽到',
  PRIMARY KEY(session_id,question_id),
  UNIQUE KEY uk_edsq_plan_employee_question(plan_id,employee_id,question_id),
  CONSTRAINT fk_edsq_session FOREIGN KEY(session_id) REFERENCES exam_daily_session(id) ON DELETE CASCADE,
  CONSTRAINT fk_edsq_question FOREIGN KEY(question_id) REFERENCES question_bank(id),
  INDEX idx_edsq_session_sort(session_id,sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. 作答与判分
CREATE TABLE exam_daily_answer (
  session_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  answer_json JSON NULL,
  is_correct BOOLEAN NULL,
  score DECIMAL(5,2) NULL,
  saved_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(session_id,question_id),
  CONSTRAINT fk_eda_session FOREIGN KEY(session_id) REFERENCES exam_daily_session(id) ON DELETE CASCADE,
  CONSTRAINT fk_eda_question FOREIGN KEY(question_id) REFERENCES question_bank(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7. 每日生成任务日志（幂等）
CREATE TABLE exam_daily_job_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id BIGINT NOT NULL,
  practice_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
    COMMENT 'RUNNING/SUCCESS/FAILED/SKIPPED',
  generated_count INT NOT NULL DEFAULT 0,
  failed_count INT NOT NULL DEFAULT 0,
  error_message VARCHAR(500) NULL,
  started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at DATETIME NULL,
  UNIQUE KEY uk_edjr_plan_date(plan_id,practice_date),
  CONSTRAINT fk_edjr_plan FOREIGN KEY(plan_id) REFERENCES exam_daily_plan(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 8. 排除日期（节假日）
CREATE TABLE exam_daily_plan_exclusion (
  plan_id BIGINT NOT NULL,
  exclude_date DATE NOT NULL,
  reason VARCHAR(200),
  PRIMARY KEY(plan_id,exclude_date),
  CONSTRAINT fk_edpe_plan FOREIGN KEY(plan_id) REFERENCES exam_daily_plan(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 9. 抽题性能索引（现有只有 question_type+enabled 联合索引）
ALTER TABLE question_bank ADD INDEX idx_question_bank_bank_type_enabled(bank_id,question_type,enabled);
