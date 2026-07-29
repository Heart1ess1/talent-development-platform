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
| `employee:read` | 按人员范围查询统一人员信息、导师列表依赖 | 是 | 是 | 是 | 是 | 是 | 是 |
| `employee:update` | 更新现有人员档案 | 否 | 否 | 否 | 是 | 是 | 是 |
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
| `master:manage` | 创建批次和服务站、审批服务站变更 | 否 | 否 | 否 | 否 | 是 | 是 |
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
- `EMPLOYEE` 不进入统一人员台账管理页，应在个人资料页查看本人工作信息。
- 员工可直接维护本人非工作安排类个人资料，包括联系方式、私人邮箱、生日、籍贯、公司住址、毕业学校、所学专业和学历；工号、姓名、批次、所属板块、服务站点、双导师、入职日期和状态只读展示，仍只能由管理员维护。
- 员工不能直接修改服务站，只能提交一条待审批的变更申请；`ADMIN` 或 `SUPER_ADMIN` 审批通过后才更新人员信息。管理员在统一人员信息页直接调整站点时也会生成已生效历史。
- 位置报备不等同于服务站变更：`EMPLOYEE` 只能提交和查看本人位置；`MENTOR` 查看所带员工，`STATION_MANAGER` 查看所辖站点员工，`TRAINING_ADMIN`、`ADMIN`、`SUPER_ADMIN` 查看全部员工。管理侧只读追踪，不审批、不修改员工报备。
- 导师、服务站负责人和培训管理员账号创建、启停、重置密码和服务站范围设置要求 `user:ops-role:manage`，当前 `ADMIN` 和 `SUPER_ADMIN` 拥有。
- 管理员和超级管理员账号创建、系统角色调整要求 `user:admin:manage`，当前只有 `SUPER_ADMIN` 拥有。
- 停用当前账号被禁止；停用最后一个启用状态的 `SUPER_ADMIN` 被禁止。

## 前端路由权限

| 页面 | 路由 | 前端进入条件 |
| --- | --- | --- |
| 登录 | `/login` | 未登录可访问。 |
| 进度概览 | `/dashboard` | 已登录。 |
| 位置报备 / 人员流动 | `/location-reports` | `employee:read`；员工进入本人报备界面，其他角色进入按数据范围过滤的人员流动看板。 |
| 人员管理 / 人员台账 | `/employee-directory` | `employee:read` 且非 `EMPLOYEE`；页面内新增编辑、导入导出和基础数据操作继续按具体权限控制。 |
| 人员管理 / 人员流动 | `/location-reports` | `employee:read` 且非 `EMPLOYEE`；按数据范围展示人员位置变化。员工访问同一路由时仍显示独立的“位置报备”入口。 |
| 课程管理 / 课程库 | `/courses/manage` | `course:manage`；维护课程生命周期和课程课件。 |
| 课程管理 / 场次安排 | `/courses/sessions` | `course:manage`；维护场次、签到窗口和参加人员。 |
| 课程学习 / 我的课程 | `/courses/my` | 仅员工；查看本人场次、课件并完成签到。 |
| 签到管理 / 签到记录 | `/courses/attendance` | 已登录并按员工数据范围过滤；补录和导入需要 `attendance:manage`。旧地址 `/courses` 按角色自动跳转。 |
| 培养计划 / 计划管理 | `/training-plans/manage` | 需要 `task:manage`；维护计划信息、复制、启停与删除。 |
| 培养计划 / 任务编排 | `/training-plans/tasks` | 需要 `task:manage`；维护任务内容、附件与执行顺序。旧地址 `/training-plans` 自动跳转到计划管理。 |
| 培养计划 / 任务下发 | `/tasks` | 管理侧需要 `task:manage`；员工侧显示“我的任务”，只访问本人任务。 |
| 培养计划 / 任务跟踪 | `/training-plans/tracking` | 已登录，按员工数据范围查询任务执行情况；审核操作额外需要 `task:review`。 |
| 综合评价 | `/evaluation` | `evaluation:view`。 |
| 考试中心 | `/exams` | 已登录，页面内功能按权限和角色区分。 |
| 账号管理 | `/users` | `user:employee:manage`；用于管理全体人员的账号、角色与数据范围。 |
| 人员管理 / 调站审批 | `/station-change-review` | `master:manage`；查看审批统计和完整申请背景，通过申请会立即更新人员归属，拒绝必须填写原因。 |
| 账号设置 / 个人资料 | `/profile` | 从右上角用户菜单进入；非员工维护头像和密码，员工维护证件照、密码与本人资料。员工证件照同步作为平台头像，首次登录时强制进入。 |

## 维护规则

- 新增权限常量时，必须同步更新本文档的权限矩阵和 `docs/api-contract.md` 中相关接口权限。
- 修改 `PermissionService.permissions()` 或 `scope()` 时，必须同步更新角色矩阵。
- Controller 中新增硬编码角色判断时，必须在“特殊规则”中补充。
