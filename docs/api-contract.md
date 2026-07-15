# API 接口约定

本文档记录当前 MVP 的 `/api/v1` 接口约定。当前口径来自后端 Controller 实现，不代表未来稳定公开 API。

## 通用约定

### 认证

- 除 `/api/v1/auth/login`、前端静态资源和健康检查外，业务接口都需要登录。
- 客户端通过请求头传递 JWT：

```http
Authorization: Bearer <token>
```

- 前端请求会附带 `X-Request-Id`，但当前后端响应中的 `requestId` 由 `ApiResponse` 重新生成。

### 响应包

普通 JSON 接口统一返回：

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "requestId": "uuid"
}
```

文件下载接口直接返回文件流，不包裹 `ApiResponse`。

### 分页结构

分页接口的 `data` 为：

```json
{
  "records": [],
  "total": 0,
  "page": 1,
  "size": 20
}
```

### 常见错误

| HTTP 状态 | `code` | 场景 |
| --- | --- | --- |
| 400 | 400 | 业务校验失败或请求参数无效。 |
| 401 | 401 | 未登录、登录过期、用户名或密码错误。 |
| 403 | 403 | 无接口权限、无数据范围权限或角色不允许。 |
| 404 | 404 | 指定资源不存在。 |
| 409 | 409 | 数据重复或评价已锁定等冲突。 |
| 500 | 500 | 未捕获服务端异常。 |

## 认证与个人设置

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `POST` | `/api/v1/auth/login` | 公开 | 登录 | `username`、`password` | `token`、`user` |
| `GET` | `/api/v1/auth/me` | 登录 | 获取当前用户 | 无 | `CurrentUser` |
| `POST` | `/api/v1/auth/change-password` | 登录 | 修改当前用户密码 | `oldPassword`、`newPassword` | 新 `token`、新 `user` |
| `GET` | `/api/v1/profile/employee` | `EMPLOYEE` 本人 | 查询本人工作信息和可维护个人资料 | 无 | 员工个人资料，包含只读 `employee_no`、`name`、`batch_name`、`station_name`、`mentor_name`、`onboard_date`、`status` |
| `PUT` | `/api/v1/profile/employee` | `EMPLOYEE` 本人 | 维护本人非工作安排类个人资料 | `phone`、`email`、`birthDate`、`nativePlace`、`residence`、`school`、`major`、`education` | 空 |

`CurrentUser` 关键字段：`id`、`username`、`displayName`、`role`、`mustChangePassword`、`securityVersion`、`permissions`、`dataScope`。

员工个人资料接口只允许维护非工作安排字段。工号、姓名、批次、服务站、导师、入职日期和状态只读展示，不接受员工自改。

## 仪表盘

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/dashboard` | 登录，按数据范围过滤 | 进度概览 | 无 | `employeeCount`、`taskTotal`、`taskCompleted`、`averageScore`、`attendanceCount`、`scoreDistribution` |

## 员工台账

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/employees` | `employee:read`，非 `EMPLOYEE`，按数据范围过滤 | 分页查询员工 | `page`、`size`、`keyword`、`batchId`、`stationId`、`mentorId` | 分页员工列表 |
| `POST` | `/api/v1/employees` | `employee:write` | 创建员工和关联员工账号 | `employeeNo`、`name`、`batchId`、`stationId`、`mentorUserId`、`email` 等 | 员工 ID |
| `PUT` | `/api/v1/employees/{id}` | `employee:write` | 更新员工和关联账号基础信息 | 同创建员工 | 空 |
| `POST` | `/api/v1/employees/bind-mentor` | `employee:write` | 批量绑定导师 | `employeeIds`、`mentorUserId` | 更新数量 |
| `GET` | `/api/v1/employees/{id}` | `employee:read`，非 `EMPLOYEE`，按数据范围校验 | 查询员工详情 | 路径 `id` | 员工详情 |

员工创建会同步创建 `EMPLOYEE` 账号，默认停用并要求改密。员工本人不通过员工台账查看本人资料，应使用 `/api/v1/profile/employee`。

## 人员目录与导入

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/employee-directory` | `employee:read`，按数据范围过滤 | 人员目录分页查询 | `page`、`size`、`keyword`、`batchId`、`stationId`、`mentorId`、`education`、`status` | 分页人员目录 |
| `GET` | `/api/v1/employee-directory/export` | `employee:export`，按数据范围过滤 | 导出人员目录 Excel | 同目录筛选参数 | Excel 文件 |
| `GET` | `/api/v1/imports/employees/template` | `employee:write` | 下载员工导入模板 | 无 | Excel 文件 |
| `POST` | `/api/v1/imports/employees` | `employee:write` | 导入员工 | `multipart/form-data` 字段 `file` | `imported`、`errors` |

导入员工采用整批校验；如存在错误，返回 `imported=0` 和行级错误，不写入任何员工。

## 基础数据

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/batches` | 登录 | 查询培养批次 | 无 | 批次列表 |
| `POST` | `/api/v1/batches` | `master:manage` | 创建培养批次 | `name` | 批次 ID |
| `GET` | `/api/v1/stations` | 登录 | 查询服务站 | 无 | 服务站列表 |
| `POST` | `/api/v1/stations` | `master:manage` | 创建服务站 | `name` | 服务站 ID |
| `GET` | `/api/v1/mentors` | `employee:read` | 查询启用导师账号 | 无 | `id`、`display_name` |

## 课程与签到

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/courses` | 登录，员工仅看已安排课程 | 查询课程 | 无 | 课程列表 |
| `POST` | `/api/v1/courses` | `course:manage` | 创建课程 | `name`、`description` | 课程 ID |
| `GET` | `/api/v1/sessions` | 登录，按数据范围过滤 | 查询课程场次 | 无 | 场次列表 |
| `POST` | `/api/v1/sessions` | `course:manage` | 创建课程场次 | `courseId`、`title`、`location`、`hours`、`startsAt`、`endsAt`、`checkinStartsAt`、`checkinEndsAt` | `id`、`checkinCode` |
| `POST` | `/api/v1/sessions/{id}/enroll` | `course:manage` | 安排员工参加场次 | `employeeIds` | 新增安排数量 |
| `POST` | `/api/v1/attendance/checkin` | 角色 `EMPLOYEE` | 员工签到 | `code`，6 位数字 | 空 |
| `POST` | `/api/v1/attendance/manual` | `attendance:manage` | 签到补录 | `sessionId`、`employeeId`、`remark` | 空 |
| `GET` | `/api/v1/attendance` | 登录，按数据范围过滤 | 查询签到记录 | 可选 `employeeId` | 签到列表 |
| `GET` | `/api/v1/imports/attendance/template` | `attendance:manage` | 下载签到导入模板 | 无 | Excel 文件 |
| `POST` | `/api/v1/imports/attendance` | `attendance:manage` | 导入签到记录 | `multipart/form-data` 字段 `file` | `imported`、`errors` |

场次结束时间必须晚于开始时间，签到结束时间必须晚于签到开始时间。员工签到要求当前时间在签到窗口内，且本人已被安排到该场次。

## 闯关任务

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/tasks` | 登录，按数据范围过滤 | 查询任务 | 无 | 任务列表 |
| `POST` | `/api/v1/tasks` | `task:manage` | 创建任务 | `title`、`description`、`requirements`、`deadline` | 任务 ID |
| `GET` | `/api/v1/tasks/{id}` | 登录，按数据范围过滤 | 查询任务详情 | 路径 `id` | 任务完整内容 |
| `PUT` | `/api/v1/tasks/{id}` | `task:manage` | 编辑任务完整内容 | `title`、`description`、`requirements`、`deadline` | 空 |
| `POST` | `/api/v1/assignments/assign` | `task:manage` | 分配任务 | `taskId`，以及 `employeeIds`、`batchId`、`stationId` 至少一类 | 新增分配数量 |
| `POST` | `/api/v1/tasks/dispatch-manual` | `task:manage` | 手动创建并下达任务 | `title`、`description`、`requirements`、`deadline`，以及目标人员条件 | `taskId`、`assignedEmployees` |
| `GET` | `/api/v1/tasks/{id}/progress` | 登录，按数据范围过滤 | 查询任务对应员工的完成情况 | 路径 `id` | 下达日期、提交日期、状态、评分 |
| `DELETE` | `/api/v1/tasks/{id}` | `task:manage` | 删除无提交记录的任务 | 路径 `id` | 空 |
| `GET` | `/api/v1/assignments` | 登录，按数据范围过滤 | 查询任务分配 | 可选 `status` | 分配列表 |
| `GET` | `/api/v1/assignments/pending-review` | `task:review`，按数据范围过滤 | 查询待审核任务 | 无 | 待审核的任务分配与最新提交信息 |
| `GET` | `/api/v1/assignments/{id}/submissions` | 登录，按任务员工范围校验 | 查询提交历史 | 路径 `id` | 提交版本和附件列表 |
| `POST` | `/api/v1/assignments/{id}/submissions` | 角色 `EMPLOYEE`，本人任务 | 提交任务成果 | `multipart/form-data` 字段 `content`、`files` | 提交 ID |
| `POST` | `/api/v1/submissions/{id}/review` | `task:review` | 审核任务提交 | `decision=APPROVE|RETURN`、`comment`、`score` | 空 |

任务提交允许 `NOT_SUBMITTED`、`RETURNED` 与截止前的 `PENDING_REVIEW` 重新提交；系统维护最近一项未提交任务的截止时间定时器，在截止时间到达时立即将仍未提交的分配固化为 `OVERDUE` 并记 0 分，服务启动和任务变更后会自动重排该定时器。审核接口同时校验 `task:review` 权限和提交员工的数据范围。单次最多上传 5 个附件，文件扩展名限制为 `pdf`、`doc`、`docx`、`xls`、`xlsx`、`ppt`、`pptx`、`png`、`jpg`、`jpeg`、`zip`。

## 综合评价

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/evaluation/schemes` | `evaluation:manage` | 查询评分方案 | 可选 `batchId` | 方案列表 |
| `POST` | `/api/v1/evaluation/schemes` | `evaluation:manage` | 创建评分方案草稿 | `batchId`、`effectiveMonth`、五类评分项启停和权重、季度权重、加扣分上限 | 方案 ID |
| `PUT` | `/api/v1/evaluation/schemes/{id}` | `evaluation:manage` | 更新草稿方案 | 同创建方案 | 空 |
| `POST` | `/api/v1/evaluation/schemes/{id}/draft` | `evaluation:manage` | 从已发布或已退役方案复制新草稿 | 路径 `id` | 新方案 ID |
| `DELETE` | `/api/v1/evaluation/schemes/{id}` | `evaluation:manage` | 删除方案 | 路径 `id` | 空 |
| `POST` | `/api/v1/evaluation/schemes/{id}/publish` | `evaluation:manage` | 发布草稿方案 | 路径 `id` | 空 |
| `GET` | `/api/v1/evaluation/monthly/detail` | `evaluation:view`，非 `EMPLOYEE`，按数据范围校验 | 查询月度评分明细 | `employeeId`、`month` | 月度明细 |
| `PUT` | `/api/v1/evaluation/monthly/components/{component}` | `evaluation:submit`，按角色限制评分项 | 提交指定评分项 | `employeeId`、`month`、`score`、`comment` | 空 |
| `POST` | `/api/v1/evaluation/monthly` | `evaluation:submit`，按角色推导评分项 | 兼容旧客户端的月度评价提交 | `employeeId`、`month`、`score`、`comment` | 空 |
| `PUT` | `/api/v1/evaluation/monthly/overrides/{component}` | `ADMIN` 或 `SUPER_ADMIN` | 覆盖评分项 | `employeeId`、`month`、`score`、`reason` | 空 |
| `DELETE` | `/api/v1/evaluation/monthly/overrides/{component}` | `ADMIN` 或 `SUPER_ADMIN` | 删除评分项覆盖 | `employeeId`、`month` | 空 |
| `GET` | `/api/v1/evaluation/monthly` | `evaluation:view`，按数据范围过滤 | 查询月度评价记录 | `employeeId` | 月评列表 |
| `POST` | `/api/v1/evaluation/adjustments` | `evaluation:manage` | 新增加扣分 | `employeeId`、`month`、`type=BONUS|DEDUCTION`、`points`、`reason`、`evidenceFileId` | 调整 ID |
| `POST` | `/api/v1/evaluation/summaries/generate-month` | `evaluation:manage` | 生成月度汇总 | `month` | 生成数量 |
| `POST` | `/api/v1/evaluation/summaries/generate-quarter` | `evaluation:manage` | 生成季度汇总 | `year`、`quarter` | 生成数量 |
| `GET` | `/api/v1/evaluation/summaries` | `evaluation:view`，按数据范围过滤 | 查询汇总 | `employeeId` | 汇总列表 |
| `POST` | `/api/v1/evaluation/summaries/{id}/publish` | `evaluation:manage` | 发布汇总 | `waiverReason`、`overrideScore` | 空 |
| `POST` | `/api/v1/evaluation/summaries/{id}/reopen` | `ADMIN` 或 `SUPER_ADMIN` | 重开已发布月度汇总 | `reason` | 新汇总 ID |

评分项包括 `EXAM`、`TASK`、`MENTOR`、`STATION`、`TRAINING`。已发布月度汇总会锁定对应月份，除管理员重开外不可继续修改。

## 考试中心

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `POST` | `/api/v1/exams/questions` | `exam:manage` | 创建题目 | `type=SINGLE|MULTIPLE|TRUE_FALSE`、`stem`、`options`、`answer`、`explanation`、`score` | 题目 ID |
| `GET` | `/api/v1/exams/questions` | `exam:manage` | 查询题库 | 可选 `type`、`keyword` | 题目列表 |
| `PUT` | `/api/v1/exams/questions/{id}/enabled` | `exam:manage` | 启用或停用题目 | `enabled` | 空 |
| `GET` | `/api/v1/exams/questions/template` | `exam:manage` | 下载题库导入模板 | 无 | Excel 文件 |
| `POST` | `/api/v1/exams/questions/import` | `exam:manage` | 导入题库 | `multipart/form-data` 字段 `file` | `imported`、`errors` |
| `POST` | `/api/v1/exams/papers` | `exam:manage` | 创建试卷 | `name`、`description`、`randomAssembly`、`randomizeQuestions`、`randomizeOptions`、`questions` 或 `randomRules` | 试卷 ID |
| `GET` | `/api/v1/exams/papers` | `exam:manage` | 查询试卷 | 无 | 试卷列表 |
| `POST` | `/api/v1/exams/plans` | `exam:manage` | 创建考试计划 | `paperId`、`name`、`batchId`、`startsAt`、`endsAt`、`durationMinutes`、`maxAttempts`、`scoreMonth`、`employeeIds` | 计划 ID |
| `POST` | `/api/v1/exams/plans/{id}/publish` | `exam:manage` | 发布考试计划 | 路径 `id` | 空 |
| `POST` | `/api/v1/exams/plans/{id}/assign` | `exam:manage` | 补充分配考试 | `employeeIds` | 新增分配数量 |
| `GET` | `/api/v1/exams/plans` | 登录，按数据范围过滤 | 查询考试计划 | 无 | 计划列表；员工记录额外包含 `plan_phase`、`participation_status` 和 `attempt_count` |
| `POST` | `/api/v1/exams/plans/{id}/attempts` | 角色 `EMPLOYEE`，本人已分配 | 开始或继续考试 | 路径 `id` | 答题记录和题目 |
| `GET` | `/api/v1/exams/attempts/{id}` | `exam:manage` 或考生本人 | 查看答卷 | 路径 `id` | 答卷和题目 |
| `PUT` | `/api/v1/exams/attempts/{id}/answers` | 考生本人，进行中 | 保存答案 | `questionId`、`answer` | 空 |
| `POST` | `/api/v1/exams/attempts/{id}/events` | 考生本人，进行中 | 记录防作弊事件 | `type=BLUR|HIDDEN|EXIT_FULLSCREEN|RECONNECT`、`detail` | 空 |
| `POST` | `/api/v1/exams/attempts/{id}/submit` | 考生本人，进行中 | 提交答卷并触发评分 | 路径 `id` | 评分结果 |
| `GET` | `/api/v1/exams/review` | `exam:manage` | 查询阅卷队列 | 无 | 待阅卷/已评分答卷 |
| `PUT` | `/api/v1/exams/attempts/{attemptId}/questions/{questionId}/grade` | `exam:manage` | 主观题评分 | `score`、`comment` | 空 |
| `POST` | `/api/v1/exams/attempts/{id}/publish` | `exam:manage` | 发布考试结果 | 路径 `id` | 空 |
| `GET` | `/api/v1/exams/results` | 登录，按数据范围过滤 | 查询已发布考试结果与已结束考试的缺考记录 | 可选 `employeeId` | 结果列表，包含 `result_status=COMPLETED|ABSENT`；缺考记录的 `total_score=0` |

当前题型校验只允许 `SINGLE`、`MULTIPLE`、`TRUE_FALSE`。试卷总分必须等于 100 分。考试计划的 `plan_phase` 为 `DRAFT`、`UPCOMING`、`OPEN`、`ENDED`；员工的 `participation_status` 为 `NOT_STARTED`、`READY`、`IN_PROGRESS`、`PENDING_REVIEW`、`COMPLETED`、`ABSENT`。其中 `ABSENT` 为实时派生状态：计划已结束且员工从未产生答卷时显示为缺考，不额外创建考试记录。

## 账号管理

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/users` | `user:employee:manage` | 查询账号 | 可选 `role`；按当前账号可管理角色返回，站点负责人含 `station_ids`、`station_names` | 账号列表 |
| `POST` | `/api/v1/users` | 运营角色需 `user:ops-role:manage`，管理员需 `user:admin:manage` | 创建非员工账号 | `username`、`displayName`、`role=MENTOR|STATION_MANAGER|TRAINING_ADMIN|ADMIN`、`stationIds` | `id`、`temporaryPassword` |
| `PUT` | `/api/v1/users/{id}/enabled` | 员工账号需 `user:employee:manage`，运营角色需 `user:ops-role:manage`，管理员角色需 `user:admin:manage` | 启停账号 | `enabled` | 空 |
| `PUT` | `/api/v1/users/{id}/role` | `user:admin:manage` | 修改非当前账号角色 | 普通账号支持 `MENTOR|TRAINING_ADMIN|ADMIN|SUPER_ADMIN`；关联员工档案的账号角色固定为 `EMPLOYEE`，仅允许将历史异常角色恢复为 `EMPLOYEE` | 空 |
| `PUT` | `/api/v1/users/{id}/display-name` | `user:admin:manage` | 修改账号姓名 | `displayName`；关联员工档案的账号会同步更新员工姓名 | 空 |
| `POST` | `/api/v1/users/{id}/reset-password` | 员工账号需 `user:employee:manage`，运营角色需 `user:ops-role:manage`，管理员角色需 `user:admin:manage` | 重置密码 | 路径 `id` | `temporaryPassword` |
| `PUT` | `/api/v1/users/{id}/stations` | `user:ops-role:manage` | 设置站点负责人服务站范围 | `stationIds` | 空 |

站点负责人必须至少绑定一个服务站。账号创建和重置密码返回临时密码，用户后续需要修改密码。

## 文件与审计

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/files/{id}` | 登录，按任务所属员工范围校验 | 下载任务附件 | 路径 `id` | 文件流 |
| `GET` | `/api/v1/audit-logs` | `audit:read` | 查询审计日志 | `limit`，范围 1 到 500，默认 100 | 审计日志列表 |

审计日志记录操作人、动作、目标类型、目标 ID、请求 ID、变更前后值和创建时间。

## 阶段 3 培养计划与任务模板

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/training-plans` | `task:manage` | 查询培养计划 | 无 | 计划列表和任务数量 |
| `POST` | `/api/v1/training-plans` | `task:manage` | 创建培养计划 | `name`、`description` | 计划 ID |
| `PUT` | `/api/v1/training-plans/{id}` | `task:manage` | 编辑培养计划 | `name`、`description` | 空 |
| `PUT` | `/api/v1/training-plans/{id}/enabled` | `task:manage` | 启停培养计划 | `enabled` | 空 |
| `GET` | `/api/v1/training-plans/{id}/tasks` | `task:manage` | 查询计划任务编排 | 路径 `id` | 编排列表 |
| `POST` | `/api/v1/training-plans/{id}/tasks` | `task:manage` | 新建计划任务 | `title`、`description`、`requirements` | 计划任务 ID |
| `PUT` | `/api/v1/training-plans/{planId}/tasks/{taskId}` | `task:manage` | 编辑计划任务 | `title`、`description`、`requirements` | 空 |
| `DELETE` | `/api/v1/training-plans/{planId}/tasks/{taskId}` | `task:manage` | 删除未下达的计划任务 | 路径 `planId`、`taskId` | 空 |
| `PUT` | `/api/v1/training-plans/{id}/tasks/order` | `task:manage` | 调整计划任务顺序 | `items: [{id, sortOrder}]` | 空 |
| `POST` | `/api/v1/tasks/dispatch-plan` | `task:manage` | 从计划下达选定任务 | `planId`、`planTaskIds`、可选 `taskTitle`、`deadlineMode`、目标人员条件 | `targetEmployees`、`createdTasks`、`createdAssignments` |

培养计划仅编排任务标题、任务说明和成果要求，不包含截止时间。计划任务应在闯关任务页面按需下达，目标人员条件可组合 `employeeIds`、`batchId`、`stationId`，并按并集筛选 `ACTIVE` 员工。`taskTitle` 可选，留空时使用每个计划任务的名称，填写后作为本次下达任务的统一名称。`deadlineMode` 支持：`OFFSET`（`baseDate + offsetDays`）和 `ABSOLUTE`（`deadlineDate`）；均在当日 `23:59:59` 截止。下达结果关联 `training_plan_task_id`，同一计划任务和截止日期会复用任务，避免重复分配。手动创建和下达任务接口保持不变。
