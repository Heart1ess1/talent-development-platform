-- V54: 人员档案新增字段（技术/技能指导老师联系方式、培养周期）
-- 只新增列与字典选项；不修改、不删除 employee 现有数据，也不改动 V1–V53。

ALTER TABLE employee
  ADD COLUMN mentor_phone VARCHAR(32) NULL COMMENT '技术指导老师联系方式',
  ADD COLUMN skill_mentor_phone VARCHAR(32) NULL COMMENT '技能指导老师联系方式',
  ADD COLUMN training_cycle_id BIGINT NULL COMMENT '培养周期（dictionary_item.id）',
  ADD KEY idx_employee_training_cycle (training_cycle_id);

-- 预置培养周期字典选项，可在“字典值管理”中继续新增、修改、停用。
INSERT INTO dictionary_item (type_code, item_value, label, sort_order, enabled) VALUES
  ('TRAINING_CYCLE', '3M', '3个月', 1, TRUE),
  ('TRAINING_CYCLE', '6M', '6个月', 2, TRUE),
  ('TRAINING_CYCLE', '1Y', '1年', 3, TRUE);
