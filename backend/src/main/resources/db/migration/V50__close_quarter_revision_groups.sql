-- Preserve removed members for audit while removing them from active review.
CREATE TABLE evaluation_revision_member_archive LIKE evaluation_revision_member;
INSERT INTO evaluation_revision_member_archive
SELECT m.* FROM evaluation_revision_member m JOIN evaluation_revision_group g ON g.id=m.group_id
WHERE m.summary_type='QUARTER' AND g.status='DRAFT';

UPDATE evaluation_revision_group g
SET g.status='SUPERSEDED',g.reason=CONCAT(LEFT(COALESCE(g.reason,''),400),' [季度评价关闭]')
WHERE g.status='DRAFT' AND NOT EXISTS (
  SELECT 1 FROM evaluation_revision_member m WHERE m.group_id=g.id AND m.summary_type='MONTH'
);
DELETE m FROM evaluation_revision_member m JOIN evaluation_revision_member_archive a
ON a.group_id=m.group_id AND a.employee_id=m.employee_id AND a.period_key=m.period_key AND a.summary_type=m.summary_type;

UPDATE evaluation_revision_group g
SET g.generation=g.generation+1,g.reason=CONCAT(LEFT(COALESCE(g.reason,''),400),' [移除季度结果成员]')
WHERE g.status='DRAFT' AND EXISTS (
  SELECT 1 FROM evaluation_revision_member_archive a WHERE a.group_id=g.id
);
