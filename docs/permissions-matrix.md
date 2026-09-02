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
| `task:score` | 进入任务评分工作台；实际评分仍要求本人被配置为评分人 | 否 | 是 | 是 | 是 | 是 | 是 |
| `evaluation:view` | 查看评价、汇总和评价页面 | 是 | 是 | 是 | 是 | 是 | 是 |
| `evaluation:submit` | 提交角色对应的月度评价项 | 否 | 是 | 是 | 是 | 否 | 否 |
| `evaluation:manage` | 评分方案、加扣分、汇总生成和发布 | 否 | 否 | 否 | 是 | 是 | 是 |
| `exam:manage` | 题库、客观题试卷、考试计划、即时成绩核对和历史主观题阅卷 | 否 | 否 | 否 | 是 | 是 | 是 |
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
- 当某月已生成显式评分任务时，人工评分还必须校验当前账号是否为该任务的有效评分人；显式分配优先于旧的导师/站点数据范围推导。没有显式任务的历史月份继续按原数据范围兼容。
- 评分人范围配置需要 `evaluation:manage`；全员、批次、板块规则只会选择与评分项角色匹配的启用账号，匹配优先级为板块、批次、全员，已发布月份保持锁定。
- `EMPLOYEE` 的课程签到接口要求角色必须是 `EMPLOYEE`。
- `EMPLOYEE` 的任务提交接口要求角色必须是 `EMPLOYEE`，且只能提交本人任务。
- 任务评分范围可按批次、板块、班级任意组合，范围内条件按交集匹配；评分人只能选择启用的非员工账号。`TRAINING_ADMIN`、`ADMIN`、`SUPER_ADMIN` 可以查看全部任务和范围并配置评分人，但不能评分未分配给自己的员工成果；`MENTOR`、`STATION_MANAGER` 只可查看本人范围及对应员工。
- `task:review` 仅为旧入口兼容权限，不能绕过任务评分范围绑定；新旧评分接口统一按 `task:score`、员工分配记录的 `scoring_scope_id` 和范围成员关系校验。
- 员工开考和考试答题要求角色必须是 `EMPLOYEE`，且考试已发布、在开放时间内、本人已被分配且次数未用完。
- 考试答卷查看允许 `exam:manage` 用户查看；否则只允许考生本人查看。
- 月度评价明细 `/evaluation/monthly/detail` 不允许 `EMPLOYEE` 直接查看，员工只能查看已发布结果。
- 评分项覆盖、删除覆盖和重开已发布月度汇总要求当前角色是 `ADMIN` 或 `SUPER_ADMIN`。
- `MENTOR` 可查看并评价技术导师或技能导师字段中关联的员工；两位导师分别提交，系统在全部应评导师完成后取平均分。
- `STATION_MANAGER` 可查看员工当月实际在站记录中本人负责的站点，并且只能提交该站点评价；员工跨站后的历史月份不会因当前归属变化而失去评价权限。
- 员工当月站点权重的人工设置和恢复自动计算仅允许 `ADMIN` 或 `SUPER_ADMIN`，且必须覆盖当月全部实际站点并合计为 100%。
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
| 进度概览 | `/dashboard` | 已登录；员工显示个人学习主页，其他角色按数据范围显示培养运营工作台，职责待办再按具体业务权限过滤。 |
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
| 培养计划 / 任务跟踪 | `/training-plans/tracking` | 已登录，按员工数据范围查询任务执行情况；评分人、评分进度和最终分数均为只读信息。 |
| 培养计划 / 任务评分 | `/task-scoring` | `task:score`；全局评分管理角色查看全部任务，导师和服务站负责人只查看本人评分任务，评分操作还必须校验本人被分配。 |
| 综合评价 / 评价工作台 | `/evaluation/workbench` | `evaluation:view`；员工自动转到“我的评价”。 |
| 综合评价 / 评分任务 | `/evaluation/assignments` | `evaluation:manage`；选择导师/站点/培训任务，按全员、批次或板块统一配置多名评分人并查询覆盖进度。 |
| 综合评价 / 评分任务详情 | `/evaluation/assignments/:id` | `evaluation:manage`；只读查看员工任务的匹配依据、每位评分人的提交和个人分数。 |
| 综合评价 / 我的评分任务 | `/evaluation/my-tasks` | `evaluation:submit`；仅显示明确分配给当前账号的任务。 |
| 综合评价 / 月度评分 | `/evaluation/monthly` | `evaluation:view`；人工录分还需 `evaluation:submit`，员工不可进入。 |
| 综合评价 / 评价模板 | `/evaluation/templates` | `evaluation:manage`。 |
| 综合评价 / 结果中心 | `/evaluation/results` | `evaluation:view`；非管理角色只返回已发布结果，员工仅查看本人。旧地址 `/evaluation` 按角色自动跳转。 |
| 考试中心 | `/exams` | 已登录，页面内功能按权限和角色区分。 |
| 账号管理 | `/users` | `user:employee:manage`；用于管理全体人员的账号、角色与数据范围。 |
| 人员管理 / 调站审批 | `/station-change-review` | `master:manage`；查看审批统计和完整申请背景，通过申请会立即更新人员归属，拒绝必须填写原因。 |
| 账号设置 / 个人资料 | `/profile` | 从右上角用户菜单进入；非员工维护头像和密码，员工维护证件照、密码与本人资料。员工证件照同步作为平台头像，首次登录时强制进入。 |

## 维护规则

- 新增权限常量时，必须同步更新本文档的权限矩阵和 `docs/api-contract.md` 中相关接口权限。
- 修改 `PermissionService.permissions()` 或 `scope()` 时，必须同步更新角色矩阵。
- Controller 中新增硬编码角色判断时，必须在“特殊规则”中补充。
