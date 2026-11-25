-- 过期扫描使用 status + deadline_at 过滤，补二级索引避免每 60 秒全表扫描。
CREATE INDEX idx_eds_status_deadline ON exam_daily_session (status, deadline_at);
