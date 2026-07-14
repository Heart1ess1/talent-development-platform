# 权限矩阵

本文档固化当前 MVP 的角色、权限点和数据范围。当前口径来自 `Permissions.java`、`PermissionService.java`、前端路由权限和各 Controller 中的额外角色校验。

## 角色与数据范围

| 角色 | 数据范围 | 范围说明 |
| --- | --- | --- |
| `EMPLOYEE` | `SELF` | 只能访问本人对应的员工数据。 |
| `MENTOR` | `MENTORED` | 只能访问 `employee.mentor_user_id` 指向自己的员工。 |
| `STATION_MANAGER` | `STATION` | 只能访问自己绑定服务站下的员工，绑定关系来自 `station_manager_scope`。 |
| `TRAINING_ADMIN` | `ALL` | 可访问全部员工数据。 |
| `ADMIN` | `ALL` | 可访问全部员工数据。 |
| `SUPER_ADMIN` | `ALL` | 可访问全部员工数据。 |

数据范围由后端 `PermissionService.employeeFilter()` 和 `PermissionService.requireEmployee()` 强制执行。前端菜单隐藏只是辅助体验，不是安全边界。

## 权限点矩阵

| 权限点 | 用途 | EMPLOYEE | MENTOR | STATION_MANAGER | TRAINING_ADMIN | ADMIN | SUPER_ADMIN |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `employee:read` | 员工台账、人员范围内查询、导师列表依赖 | 是 | 是 | 是 | 是 | 是 | 是 |
| `employee:write` | 创建/更新员工、导入员工、绑定导师 | 否 | 否 | 否 | 否 | 是 | 是 |
| `employee:export` | 人员目录导出 | 否 | 否 | 否 | 是 | 是 | 是 |
| `course:manage` | 创建课程、场次、安排课程 | 否 | 否 | 否 | 是 | 是 | 是 |
| `attendance:manage` | 签到补录、签到导入 | 否 | 否 | 否 | 是 | 是 | 是 |
| `task:manage` | 创建和分配闯关任务 | 否 | 否 | 否 | 是 | 是 | 是 |
| `task:review` | 审核任务提交 | 否 | 否 | 否 | 是 | 是 | 是 |
| `evaluation:view` | 查看评价、汇总和评价页面 | 是 | 是 | 是 | 是 | 是 | 是 |
| `evaluation:submit` | 提交角色对应的月度评价项 | 否 | 是 | 是 | 是 | 否 | 否 |
| `evaluation:manage` | 评分方案、加扣分、汇总生成和发布 | 否 | 否 | 否 | 是 | 是 | 是 |
| `exam:manage` | 题库、试卷、考试计划、阅卷和发布成绩 | 否 | 否 | 否 | 是 | 是 | 是 |
| `user:employee:manage` | 员工账号列表、启停、重置密码 | 否 | 否 | 否 | 否 | 是 | 是 |
| `user:ops-role:manage` | 导师、服务站负责人、培训管理员账号创建、启停、重置密码和服务站范围管理 | 否 | 否 | 否 | 否 | 是 | 是 |
| `user:admin:manage` | 管理员和超级管理员账号创建、角色调整 | 否 | 否 | 否 | 否 | 否 | 是 |
| `master:manage` | 创建批次和服务站 | 否 | 否 | 否 | 否 | 是 | 是 |
| `audit:read` | 查询审计日志 | 否 | 否 | 否 | 否 | 是 | 是 |

## 特殊规则

- 所有已登录角色都拥有 `employee:read` 和 `evaluation:view`，但实际可见员工仍受数据范围限制。
- `MENTOR`、`STATION_MANAGER`、`TRAINING_ADMIN` 拥有 `evaluation:submit`，但只能提交各自对应评分项：
  - `MENTOR` 只能提交 `MENTOR` 评分项。
  - `STATION_MANAGER` 只能提交 `STATION` 评分项。
  - `TRAINING_ADMIN` 只能提交 `TRAINING` 评分项。
- `EMPLOYEE` 的课程签到接口要求角色必须是 `EMPLOYEE`。
- `EMPLOYEE` 的任务提交接口要求角色必须是 `EMPLOYEE`，且只能提交本人任务。
- 员工开考和考试答题要求角色必须是 `EMPLOYEE`，且考试已发布、在开放时间内、本人已被分配且次数未用完。
- 考试答卷查看允许 `exam:manage` 用户查看；否则只允许考生本人查看。
- 月度评价明细 `/evaluation/monthly/detail` 不允许 `EMPLOYEE` 直接查看，员工只能查看已发布结果。
- 评分项覆盖、删除覆盖和重开已发布月度汇总要求当前角色是 `ADMIN` 或 `SUPER_ADMIN`。
- 员工可直接维护本人非工作安排类个人资料，包括联系电话、常用邮箱、生日、籍贯、常住地、毕业学校、专业和学历；工号、姓名、批次、服务站、导师、入职日期和状态仍只能由管理员维护。
- 导师、服务站负责人和培训管理员账号创建、启停、重置密码和服务站范围设置要求 `user:ops-role:manage`，当前 `ADMIN` 和 `SUPER_ADMIN` 拥有。
- 管理员和超级管理员账号创建、系统角色调整要求 `user:admin:manage`，当前只有 `SUPER_ADMIN` 拥有。
- 停用当前账号被禁止；停用最后一个启用状态的 `SUPER_ADMIN` 被禁止。

## 前端路由权限

| 页面 | 路由 | 前端进入条件 |
| --- | --- | --- |
| 登录 | `/login` | 未登录可访问。 |
| 进度概览 | `/dashboard` | 已登录。 |
| 人员台账 | `/employees` | `employee:read`。 |
| 人员目录 | `/employee-directory` | `employee:export`。 |
| 课程与签到 | `/courses` | 已登录，页面内按钮按权限显示。 |
| 闯关任务 | `/tasks` | 已登录，页面内按钮按权限显示。 |
| 综合评价 | `/evaluation` | `evaluation:view`。 |
| 考试中心 | `/exams` | 已登录，页面内功能按权限和角色区分。 |
| 账号管理 | `/users` | `user:employee:manage`。 |
| 个人设置 | `/profile` | 已登录；首次登录强制进入。 |

## 维护规则

- 新增权限常量时，必须同步更新本文档的权限矩阵和 `docs/api-contract.md` 中相关接口权限。
- 修改 `PermissionService.permissions()` 或 `scope()` 时，必须同步更新角色矩阵。
- Controller 中新增硬编码角色判断时，必须在“特殊规则”中补充。
