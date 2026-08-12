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
| `POST` | `/api/v1/profile/avatar` | 登录 | 上传或更换本人头像；员工上传内容同时作为证件照 | `multipart/form-data` 字段 `file`，仅 JPG/PNG，最大 5MB，尺寸 120×120–8000×8000 | `avatarToken`、`avatarUrl` |
| `DELETE` | `/api/v1/profile/avatar` | 登录 | 删除本人头像/证件照并恢复文字头像 | 无 | 空 |
| `GET` | `/api/v1/avatars/{token}` | 公开、不可枚举随机令牌 | 读取头像图片 | 路径 `token` | 本地模式返回图片流；OSS 模式 302 到公共 CDN URL |

### 存储传输能力

| 方法 | 路径 | 权限 | 说明 | 响应 |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/storage/capabilities` | 登录 | 查询当前环境是否启用 OSS 直传和签名下载 | `directUpload`、`signedDownload` |

上传票据有效期 15 分钟，绑定创建人、用途和业务对象，只能消费一次。浏览器必须按票据返回的 `method`、`uploadUrl`、`headers` 和 `formFields` 直接上传；OSS POST Policy 会强制精确文件大小、类型和禁止覆盖。完成接口校验临时对象后复制为从未对客户端签名的正式对象，再删除临时对象。完整签名字段不应写入日志或持久化。
| `GET` | `/api/v1/profile/employee` | `EMPLOYEE` 本人 | 查询本人工作信息和可维护个人资料 | 无 | 员工个人资料，包含只读批次、所属板块、服务站点、技术/技能导师、入职日期和状态 |
| `PUT` | `/api/v1/profile/employee` | `EMPLOYEE` 本人 | 维护本人非工作安排类个人资料 | `phone`、`email`、`birthDate`、`nativePlace`、`residence`、`school`、`major`、`education` | 空 |

`CurrentUser` 关键字段：`id`、`username`、`displayName`、`role`、`mustChangePassword`、`securityVersion`、`permissions`、`dataScope`、`avatarToken`。

员工个人资料接口只允许维护非工作安排字段。工号、姓名、批次、所属板块、服务站点、技术/技能导师、入职日期和状态只读展示，不接受员工自改。

头像与员工证件照使用 `sys_user` 上的同一份媒体资产：非员工角色在界面中称为“头像”，员工角色称为“证件照”，员工证件照同步作为平台头像和人员档案照片。无照片时前端统一使用姓名末字作为文字头像。公开读取接口仅接受上传时生成的随机 UUID 令牌，不使用连续用户 ID。

## 仪表盘

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/dashboard` | 登录，按数据范围过滤 | 角色化进度概览 | 无 | 公共：`audience`、`role`、`scope_label`、`period_key`、`generated_at`；员工：`profile`、`metrics`、`action_items`、`learning_schedule`、`quarter_scores`、`mentor_feedback`、`completed_tasks`；管理：`metrics`、`operations`、`work_queue`、`schedule`、`attention_employees` |

`audience=EMPLOYEE` 时只查询当前账号关联的员工档案；季度评分和导师评价只返回已经发布的评价结果。`audience=MANAGER` 时所有员工、任务、课程、考试、评价和异常人员统计均沿用当前角色的数据范围；`work_queue` 还会根据权限移除当前角色不能处理的业务入口。

## 人员信息维护接口

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/employees` | `employee:read`，非 `EMPLOYEE`，按数据范围过滤 | 分页查询员工 | `page`、`size`、`keyword`、`batchId`、`stationId`、`mentorId` | 分页员工列表 |
| `POST` | `/api/v1/employees` | `employee:write` | 创建员工和关联员工账号 | `employeeNo`、`name`、`batchId`、`businessUnitId`、`stationId`、`mentorUserId`、`skillMentorUserId`、`status` 及个人资料字段 | 员工 ID |
| `PUT` | `/api/v1/employees/{id}` | `employee:write` | 更新人员完整档案；站点改变时自动写入已生效历史 | 同创建员工 | 空 |
| `POST` | `/api/v1/employees/bind-mentor` | `employee:write` | 批量设置技术或技能导师 | `employeeIds`、`mentorUserId`、`mentorType=TECHNICAL|SKILL` | 更新数量 |
| `GET` | `/api/v1/employees/{id}` | `employee:read`，非 `EMPLOYEE`，按数据范围校验 | 查询员工详情 | 路径 `id` | 员工详情 |

员工创建会同步创建 `EMPLOYEE` 账号，默认停用并要求改密。`/api/v1/employees` 继续供统一人员台账页面及其他业务模块复用，不再对应独立前端页面；员工本人使用 `/api/v1/profile/employee`。

### 人员接口分工与兼容约定

- `/api/v1/employees` 是人员命令与共享查询接口，负责创建、修改、批量设置导师以及供课程、任务、评价等模块选择人员。
- 现有人员更新使用 `employee:update`；培训管理员只获得该权限，不同时获得新增、导入和批量绑定所需的 `employee:write`，避免扩大写入范围。
- `/api/v1/employee-directory` 是统一人员台账工作台的扩展只读模型，包含完整档案、板块、双导师和服务站历史摘要，并提供同口径 Excel 导出。
- 两类查询都必须应用 `PermissionService.employeeFilter` 数据范围。新增筛选条件时，应确认页面查询、导出和共享人员选择是否需要同步。
- 管理员通过 `PUT /api/v1/employees/{id}` 直接改变或取消服务站分配时，后端会写入一条已生效的 `station_change_request`，不能绕过历史轨迹。
- 前端旧 `/employees` 地址只做路由重定向，不是新的 API，也不再对应独立人员台账页面。

## 人员目录与导入

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/employee-directory` | `employee:read`，按数据范围过滤 | 统一人员台账分页查询 | `page`、`size`、`keyword`、`batchId`、`businessUnitId`、`stationId`、`mentorId`、`skillMentorId`、`education`、`status` | 分页人员目录，包含证件照令牌、所属板块、双导师、完整档案字段、已生效调站次数和最近调站时间 |
| `GET` | `/api/v1/employee-directory/summary` | `employee:read`，按数据范围过滤 | 人员工作台管理概览 | 除 `status`、分页外的目录筛选参数 | 权限范围内人员总数、在职/停用人数、已分配站点人数和双导师完整人数 |
| `GET` | `/api/v1/employee-directory/export` | `employee:export`，按数据范围过滤 | 导出人员目录 Excel | 同目录筛选参数 | Excel 文件 |
| `GET` | `/api/v1/imports/employees/template` | `employee:write` | 下载员工导入模板 | 无 | Excel 文件 |
| `POST` | `/api/v1/imports/employees` | `employee:write` | 导入员工 | `multipart/form-data` 字段 `file` | `imported`、`errors` |

导入员工采用整批校验；如存在错误，返回 `imported=0` 和行级错误，不写入任何员工。

## 服务站变更申请

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `POST` | `/api/v1/station-change-requests` | 角色 `EMPLOYEE` | 申请变更本人服务站 | `stationId` | 申请 ID |
| `GET` | `/api/v1/station-change-requests?mine=true` | 角色 `EMPLOYEE` | 查询本人申请记录 | 无 | 申请列表 |
| `GET` | `/api/v1/station-change-requests` | `master:manage` | 查询待审或历史申请 | 可选 `status`、`keyword`、`stationId`、`dateFrom`、`dateTo` | 申请列表，包含人员归属、双导师、等待时长和历史调站次数 |
| `GET` | `/api/v1/station-change-requests/summary` | `master:manage` | 查询审批工作量概览 | 无 | 总申请、待审批、今日通过、今日拒绝、待办平均等待小时数 |
| `PUT` | `/api/v1/station-change-requests/{id}/approve` | `master:manage` | 审批通过并更新员工服务站 | 可选 `comment` | 空 |
| `PUT` | `/api/v1/station-change-requests/{id}/reject` | `master:manage` | 拒绝申请 | 必填 `comment` | 空 |
| `GET` | `/api/v1/station-change-requests/employee/{employeeId}` | `employee:read`，按数据范围过滤 | 查询员工已生效的调站历史 | 路径 `employeeId` | 按审批生效时间倒序的历史列表，包含原服务站、目标服务站、审批人和生效时间 |

员工同一时间只能有一条待审批申请；目标服务站必须存在、启用且不同于当前服务站。审批通过时会再次校验员工仍在职、目标站仍启用，并核对员工当前服务站与提交申请时一致，防止过期申请覆盖新的人员归属。拒绝申请必须填写可反馈给员工的原因。

## 位置报备与人员流动

位置报备用于记录员工临时外出、出差、客户现场支持等实际地点变化，不改变人员档案中的归属服务站，也不进入调站审批流程。

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `POST` | `/api/v1/location-reports` | 角色 `EMPLOYEE` | 报备本人位置变化 | `location`、`reason`、`occurredAt`、可选 `expectedReturnAt` | 报备记录 ID |
| `GET` | `/api/v1/location-reports/mine` | 角色 `EMPLOYEE` | 查询本人当前位置和最近 100 条报备 | 无 | 员工、归属站点、当前位置和历史记录 |
| `GET` | `/api/v1/location-reports` | `employee:read`，非 `EMPLOYEE`，按数据范围过滤 | 分页查询人员流动 | `page`、`size`、`keyword`、`location`、`currentOnly`、`dateFrom`、`dateTo` | 分页流动记录；包含人员、组织、导师和是否当前位置 |
| `GET` | `/api/v1/location-reports/summary` | `employee:read`，非 `EMPLOYEE`，按数据范围过滤 | 查询流动看板指标 | 无 | 总报备数、已跟踪人数、今日和近 7 日报备数 |
| `GET` | `/api/v1/location-reports/employee/{employeeId}` | `employee:read`，非 `EMPLOYEE`，按数据范围校验 | 查询单个人员流动轨迹 | 路径 `employeeId` | 按变动时间倒序的完整报备记录 |

首次报备的起点取员工当前归属服务站，后续起点由上一条报备的目标位置自动推导。变动时间不能晚于当前时间，也不能早于上一条报备；预计返回时间如填写，必须晚于变动时间。原因当前为自由文本。提交动作写入审计日志。

## 基础数据

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/batches` | 登录 | 查询培养批次 | 无 | 批次列表 |
| `POST` | `/api/v1/batches` | `master:manage` | 创建培养批次 | `name` | 批次 ID |
| `GET` | `/api/v1/stations` | 登录 | 查询服务站 | 无 | 服务站列表 |
| `POST` | `/api/v1/stations` | `master:manage` | 创建服务站 | `name` | 服务站 ID |
| `GET` | `/api/v1/business-units` | 登录 | 查询所属板块 | 无 | 板块列表 |
| `POST` | `/api/v1/business-units` | `master:manage` | 创建所属板块 | `name` | 板块 ID |
| `GET` | `/api/v1/mentors` | `employee:read` | 查询启用导师账号 | 无 | `id`、`display_name` |

## 课程与签到

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/courses` | 登录，员工仅看已安排课程 | 查询课程 | 可选 `keyword`、`includeDisabled` | 课程、场次、课件和安排数量 |
| `GET` | `/api/v1/courses/summary` | `course:manage` | 查询课程库统计 | 无 | 课程、场次、课件和安排统计 |
| `POST` | `/api/v1/courses` | `course:manage` | 创建课程 | `name`、`description` | 课程 ID |
| `PUT` | `/api/v1/courses/{id}` | `course:manage` | 编辑课程 | `name`、`description` | 空 |
| `PUT` | `/api/v1/courses/{id}/enabled` | `course:manage` | 启停课程 | `enabled` | 空 |
| `DELETE` | `/api/v1/courses/{id}` | `course:manage` | 删除从未开设场次且无课件的课程 | 路径 `id` | 空 |
| `GET` | `/api/v1/courses/{id}/materials` | 课程管理员或已安排课程数据范围 | 查询课程课件 | 路径 `id` | 课件列表 |
| `POST` | `/api/v1/courses/{id}/materials` | `course:manage` | 上传课程课件 | `multipart/form-data` 字段 `file` | 课件 ID |
| `POST` | `/api/v1/courses/{id}/materials/upload-ticket` | `course:manage` | 为课件原件申请 OSS 直传票据 | `originalName`、`contentType`、`size` | 上传票据 |
| `POST` | `/api/v1/courses/{id}/materials/upload-complete/{ticketId}` | `course:manage` | 校验 OSS 对象并创建课件记录 | 路径参数 | 课件 ID |
| `GET` | `/api/v1/course-materials/{id}` | 登录 | 已停用的原文件接口 | 任意 | `403`，不提供原文件预览或下载 |
| `DELETE` | `/api/v1/course-materials/{id}` | `course:manage` | 删除课程课件 | 路径 `id` | 空 |
| `GET` | `/api/v1/course-materials/learning` | 角色 `EMPLOYEE` | 查询本人可学习课件 | 无 | 课件及 `learned`，不含次数和时长 |
| `GET` | `/api/v1/course-materials/manage` | `course:manage` | 查询管理端课件学习概览 | 无 | 每个课件应学习、已学习、未学习人数 |
| `GET` | `/api/v1/course-materials/manage/{id}/learners` | `course:manage` | 查询课件员工学习明细 | 路径 `id` | 是否学习、学习次数、累计秒数 |
| `POST` | `/api/v1/course-materials/{id}/preview-sessions` | 课程管理员或已安排员工 | 开始安全预览 | 路径 `id` | `sessionId`、`pageCount` |
| `GET` | `/api/v1/course-materials/{id}/preview-sessions/{sessionId}/pages/{page}` | 会话所属账号 | 读取带账号水印的课件页 | 路径参数 | `image/png`，`no-store` |
| `POST` | `/api/v1/course-materials/{id}/preview-sessions/{sessionId}/heartbeat` | 会话所属账号 | 上报持续学习 | 路径参数 | 空 |
| `POST` | `/api/v1/course-materials/{id}/preview-sessions/{sessionId}/close` | 会话所属账号 | 结束学习会话 | 路径参数 | 空 |
| `GET` | `/api/v1/sessions` | 登录，按数据范围过滤 | 查询课程场次 | 可选 `courseId`、`keyword` | 场次及人员/签到数量 |
| `GET` | `/api/v1/sessions/summary` | `course:manage` | 查询场次统计 | 无 | 场次、安排和签到统计 |
| `POST` | `/api/v1/sessions` | `course:manage` | 创建课程场次 | `courseId`、`title`、`location`、`hours`、`startsAt`、`endsAt`、`checkinStartsAt`、`checkinEndsAt` | `id`、`checkinCode` |
| `PUT` | `/api/v1/sessions/{id}` | `course:manage` | 编辑课程场次 | 同创建场次 | 空 |
| `DELETE` | `/api/v1/sessions/{id}` | `course:manage` | 删除无签到历史的场次 | 路径 `id` | 空 |
| `GET` | `/api/v1/sessions/{id}/enrollments` | `course:manage` | 查询场次人员安排 | 路径 `id` | 安排及签到状态 |
| `POST` | `/api/v1/sessions/{id}/enroll` | `course:manage` | 安排员工参加场次 | `employeeIds` | 新增安排数量 |
| `DELETE` | `/api/v1/sessions/{id}/enrollments/{employeeId}` | `course:manage` | 移除尚未签到的人员安排 | 路径参数 | 空 |
| `POST` | `/api/v1/attendance/checkin` | 角色 `EMPLOYEE` | 员工签到 | `code`，6 位数字 | 空 |
| `POST` | `/api/v1/attendance/manual` | `attendance:manage` | 签到补录 | `sessionId`、`employeeId`、`remark` | 空 |
| `GET` | `/api/v1/attendance` | 登录，按数据范围过滤 | 查询签到记录 | 可选员工、课程、场次、来源、日期和关键词 | 签到列表 |
| `GET` | `/api/v1/attendance/summary` | 登录，按数据范围过滤 | 查询签到统计 | 无 | 总数、今日、自助和补录数量 |
| `GET` | `/api/v1/imports/attendance/template` | `attendance:manage` | 下载签到导入模板 | 无 | Excel 文件 |
| `POST` | `/api/v1/imports/attendance` | `attendance:manage` | 导入签到记录 | `multipart/form-data` 字段 `file` | `imported`、`errors` |

场次结束时间必须晚于开始时间，签到结束时间必须晚于签到开始时间。员工签到要求当前时间在签到窗口内、本人已被安排到该场次，且同一场次不可重复签到。人工补录会自动补齐场次人员安排并保留补录来源。已产生签到历史的场次和人员安排不可删除。

课程课件归属于课程而非单个场次，因此同一课程的后续场次可直接复用。单个课件不超过 50MB，支持 DOC/DOCX、PDF、PPT/PPTX、OFD、PNG、JPG/JPEG；上传完成后会校验 PDF、OOXML/OFD ZIP 包、OLE 或图片的真实文件结构，不能只靠修改扩展名绕过。预览会话按当前账号鉴权，员工页面水印使用员工姓名和工号；服务端通过 LibreOffice、OFDRW、PDFBox 或 ImageIO 统一输出带水印 PNG，不向前端返回原文件。单个课件最多 500 页，Office 加密文件和损坏文件无法预览。学习时长由 30 秒心跳累计，单次累计增量上限 60 秒。

## 任务下发与任务跟踪

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/tasks` | 登录，按数据范围过滤 | 查询任务 | 无 | 任务列表 |
| `POST` | `/api/v1/tasks` | `task:manage` | 创建任务 | `title`、`description`、`requirements`、`deadline` | 任务 ID |
| `GET` | `/api/v1/tasks/{id}` | 登录，按数据范围过滤 | 查询任务详情 | 路径 `id` | 任务完整内容 |
| `PUT` | `/api/v1/tasks/{id}` | `task:manage` | 编辑任务完整内容 | `title`、`description`、`requirements`、`deadline` | 空 |
| `POST` | `/api/v1/assignments/assign` | `task:manage` | 分配任务 | `taskId`，以及 `batchId`、`businessUnitId`、`stationId` 至少一类 | 新增分配数量 |
| `POST` | `/api/v1/tasks/dispatch-manual` | `task:manage` | 手动创建并下发任务 | `title`、`description`、`requirements`、`deadline`，以及可组合的 `batchId`、`businessUnitId`、`stationId` | `taskId`、`assignedEmployees` |
| `GET` | `/api/v1/tasks/{id}/progress` | 登录，按数据范围过滤 | 查询任务对应员工的完成情况 | 路径 `id` | 下发日期、提交日期、状态、评分、最新提交 ID 和附件数量 |
| `GET` | `/api/v1/tasks/{id}/progress/export` | 登录，按数据范围过滤 | 导出任务提交情况 | 路径 `id` | Excel，包含员工、时间、状态、评分、提交版本、附件数量和审核意见 |
| `GET` | `/api/v1/tasks/{id}/submissions/archive` | 登录，按数据范围过滤 | 打包导出任务全部员工提交文件 | 路径 `id` | ZIP，按“员工姓名（工号）/提交版本”分目录保存说明与附件 |
| `GET` | `/api/v1/tasks/{id}/attachments` | 登录，按数据范围过滤 | 查询已下发任务附件 | 路径 `id` | 附件列表 |
| `POST` | `/api/v1/tasks/{id}/attachments` | `task:manage` | 上传已下发或临时任务附件 | `multipart/form-data` 字段 `file` | 附件 ID |
| `POST` | `/api/v1/tasks/{id}/attachments/upload-ticket` | `task:manage` | 为任务附件申请 OSS 直传票据 | `originalName`、`contentType`、`size` | 上传票据 |
| `POST` | `/api/v1/tasks/{id}/attachments/upload-complete/{ticketId}` | `task:manage` | 校验 OSS 对象并创建任务附件 | 路径参数 | 附件 ID |
| `DELETE` | `/api/v1/tasks/{id}/attachments/{attachmentId}` | `task:manage` | 删除任务附件 | 路径参数 | 空 |
| `GET` | `/api/v1/task-attachments/{id}` | 登录，按任务数据范围过滤 | 预览或下载任务附件 | 可选 `inline` | 本地模式返回文件流；OSS 模式鉴权后 302 到 5 分钟签名 URL |
| `DELETE` | `/api/v1/tasks/{id}` | `task:manage` | 删除无提交记录的任务 | 路径 `id` | 空 |
| `GET` | `/api/v1/assignments` | 登录，按数据范围过滤 | 查询任务分配 | 可选 `status` | 分配列表 |
| `GET` | `/api/v1/assignments/pending-review` | `task:review`，按数据范围过滤 | 查询待审核任务 | 无 | 待审核的任务分配与最新提交信息 |
| `GET` | `/api/v1/assignments/{id}/submissions` | 登录，按任务员工范围校验 | 查询提交历史 | 路径 `id` | 提交版本和附件列表 |
| `POST` | `/api/v1/assignments/{id}/submission-files/upload-ticket` | 员工本人且任务可提交 | 为成果文件申请 OSS 直传票据 | `originalName`、`contentType`、`size` | 上传票据 |
| `POST` | `/api/v1/assignments/{id}/submissions/direct` | 员工本人且任务可提交 | 使用已上传票据提交或重提任务 | `content`、最多 5 个 `uploadTicketIds` | 提交 ID |
| `POST` | `/api/v1/assignments/{id}/submissions` | 角色 `EMPLOYEE`，本人任务 | 提交任务成果 | `multipart/form-data` 字段 `content`、`files` | 提交 ID |
| `GET` | `/api/v1/files/{id}` | 登录，按任务员工范围校验 | 下载单个提交附件 | 路径 `id` | 本地模式返回文件流；OSS 模式鉴权后 302 到 5 分钟签名 URL |
| `GET` | `/api/v1/submissions/{id}/files/archive` | 登录，按任务员工范围校验 | 下载单名员工单次提交资料 | 路径 `id` | ZIP，包含提交说明与全部附件 |
| `POST` | `/api/v1/submissions/{id}/review` | `task:review` | 审核任务提交 | `decision=APPROVE|RETURN`、`comment`、`score` | 空 |

任务提交允许 `NOT_SUBMITTED`、`RETURNED` 与截止前的 `PENDING_REVIEW` 重新提交；系统维护最近一项未提交任务的截止时间定时器，在截止时间到达时立即将仍未提交的分配固化为 `OVERDUE` 并记 0 分，服务启动和任务变更后会自动重排该定时器。审核接口同时校验 `task:review` 权限和提交员工的数据范围。单次最多上传 5 个附件，文件扩展名限制为 `pdf`、`doc`、`docx`、`xls`、`xlsx`、`ppt`、`pptx`、`png`、`jpg`、`jpeg`、`zip`。

## 综合评价

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/evaluation/overview` | `evaluation:view`，按数据范围过滤 | 查询指定月份评价工作台指标与跨模块待办 | `month` | 方案覆盖、汇总状态、人工评分/任务审核/遗留考试阅卷和待自动下发数量 |
| `GET` | `/api/v1/evaluation/templates` | `evaluation:manage` | 查询可复用评价模板 | 无 | 模板规则、维护人和应用次数 |
| `POST` | `/api/v1/evaluation/templates` | `evaluation:manage` | 新建评价模板 | 名称、说明、五类评分项启停/本项满分/权重、季度权重、加扣分上限 | 模板 ID |
| `PUT` | `/api/v1/evaluation/templates/{id}` | `evaluation:manage` | 编辑评价模板，不回写已应用方案 | 同创建模板 | 空 |
| `POST` | `/api/v1/evaluation/templates/{id}/copy` | `evaluation:manage` | 复制为独立模板 | 路径 `id` | 新模板 ID |
| `DELETE` | `/api/v1/evaluation/templates/{id}` | `evaluation:manage` | 停止模板后续使用，保留已应用历史 | 路径 `id` | 空 |
| `POST` | `/api/v1/evaluation/templates/apply` | `evaluation:manage` | 将模板应用到批次月份并生成方案草稿 | `templateId`、`batchId`、`effectiveMonth` | 方案 ID |
| `GET` | `/api/v1/evaluation/schemes` | `evaluation:manage` | 查询评分方案 | 可选 `batchId` | 方案列表 |
| `POST` | `/api/v1/evaluation/schemes` | `evaluation:manage` | 兼容创建评分方案草稿 | `batchId`、`effectiveMonth`、五类评分项启停/本项满分/权重、季度权重、加扣分上限 | 方案 ID |
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

评分项包括 `EXAM`、`TASK`、`MENTOR`、`STATION`、`TRAINING`。每项原始得分允许使用自己的满分口径（最高 `999.99`），计算时换算为百分比后再乘综合权重，因此启用项权重之和必须为 `100%`。已发布月度汇总会锁定对应月份，除管理员重开外不可继续修改。

## 考试中心

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/exams/question-banks` | `exam:manage` | 查询独立题库及题目统计 | 无 | 题库、题目数、可用数和启停状态 |
| `POST` | `/api/v1/exams/question-banks` | `exam:manage` | 创建独立题库 | `name`、可选 `description` | 题库 ID |
| `PUT` | `/api/v1/exams/question-banks/{id}` | `exam:manage` | 编辑或启停题库 | `name`、`description`、`enabled` | 空 |
| `POST` | `/api/v1/exams/questions` | `exam:manage` | 创建题目 | `bankId`、`type=SINGLE|MULTIPLE|TRUE_FALSE`、`stem`、`options`、`answer`、`explanation`、`score`、可选 `tags` | 题目 ID |
| `PUT` | `/api/v1/exams/questions/{id}` | `exam:manage` | 编辑题目 | 同创建题目 | 空 |
| `GET` | `/api/v1/exams/questions` | `exam:manage` | 查询题目 | 可选 `bankId`、`type`、`keyword` | 题目及所属题库列表 |
| `PUT` | `/api/v1/exams/questions/{id}/enabled` | `exam:manage` | 启用或停用题目 | `enabled` | 空 |
| `DELETE` | `/api/v1/exams/questions/{id}` | `exam:manage` | 删除未被使用的题目 | 路径 `id` | 空 |
| `GET` | `/api/v1/exams/questions/template` | `exam:manage` | 下载题库导入模板 | 无 | Excel 文件 |
| `POST` | `/api/v1/exams/questions/import` | `exam:manage` | 导入到指定题库 | `multipart/form-data` 字段 `bankId`、`file` | `imported`、`errors` |
| `POST` | `/api/v1/exams/papers` | `exam:manage` | 创建试卷 | `name`、`description`、`randomAssembly`、`dynamicAssembly`、`randomizeQuestions`、`randomizeOptions`、`questions` 或含 `tags`、`bankIds` 的 `randomRules` | 试卷 ID |
| `GET` | `/api/v1/exams/papers` | `exam:manage` | 查询试卷 | 无 | 试卷列表 |
| `GET` | `/api/v1/exams/papers/{id}` | `exam:manage` | 查看试卷结构详情 | 路径 `id` | 固定题目或随机抽题规则 |
| `DELETE` | `/api/v1/exams/papers/{id}` | `exam:manage` | 删除未被考试计划使用的试卷 | 路径 `id` | 空 |
| `GET` | `/api/v1/exams/plans/candidates` | `exam:manage` | 按批次、服务站和关键字筛选可参加考试的员工 | 可选逗号分隔 `batchIds`、`stationIds`、`keyword` | 启用状态员工列表 |
| `POST` | `/api/v1/exams/plans` | `exam:manage` | 创建考试计划 | `paperId`、`name`、`batchIds`、`stationIds`、`startsAt`、`endsAt`、`durationMinutes`、`maxAttempts`、`employeeIds` | 计划 ID |
| `POST` | `/api/v1/exams/plans/{id}/publish` | `exam:manage` | 发布考试计划 | 路径 `id` | 空 |
| `DELETE` | `/api/v1/exams/plans/{id}` | `exam:manage` | 删除考试计划草稿 | 路径 `id` | 空 |
| `POST` | `/api/v1/exams/plans/{id}/assign` | `exam:manage` | 补充分配考试 | `employeeIds` | 新增分配数量 |
| `GET` | `/api/v1/exams/plans` | 登录，按数据范围过滤 | 查询考试计划 | 无 | 计划列表；员工记录额外包含 `plan_phase`、`participation_status` 和 `attempt_count` |
| `POST` | `/api/v1/exams/plans/{id}/attempts` | 角色 `EMPLOYEE`，本人已分配 | 开始或继续考试 | 路径 `id` | 答题记录和题目 |
| `GET` | `/api/v1/exams/attempts/{id}` | `exam:manage` 或考生本人 | 查看答卷 | 路径 `id` | 答卷和题目 |
| `PUT` | `/api/v1/exams/attempts/{id}/answers` | 考生本人，进行中 | 保存答案 | `questionId`、`answer` | 空 |
| `POST` | `/api/v1/exams/attempts/{id}/events` | 考生本人，进行中 | 记录防作弊事件 | `type=BLUR|HIDDEN|EXIT_FULLSCREEN|RECONNECT`、唯一 `eventId`、`detail` | 违规次数、允许次数和自动交卷状态 |
| `POST` | `/api/v1/exams/attempts/{id}/submit` | 考生本人，进行中 | 提交答卷并触发评分 | 路径 `id` | 状态和 `scoreAvailableAt`，不返回分数 |
| `GET` | `/api/v1/exams/review` | `exam:manage`，按数据范围过滤 | 查询阅卷与待发布队列 | 无 | 待阅卷/已评分答卷、总分和发布状态 |
| `PUT` | `/api/v1/exams/attempts/{attemptId}/questions/{questionId}/grade` | `exam:manage` | 主观题评分 | `score`、`comment` | 空 |
| `POST` | `/api/v1/exams/attempts/{id}/publish` | `exam:manage` | 已停用的人工发布接口 | 路径 `id` | `400`，提示等待考试结束自动发布 |
| `POST` | `/api/v1/exams/results/manage/plans/{planId}/publish` | `exam:manage` | 已停用的批量发布接口 | 路径 `planId` | `400`，提示等待考试结束自动发布 |
| `GET` | `/api/v1/exams/results/manage` | `exam:manage` | 查询管理端已评分答卷 | 无 | 答卷、考生、考试、分数、发布状态和违规次数 |
| `GET` | `/api/v1/exams/results/manage/plans` | `exam:manage` | 查询各考试计划成绩汇总 | 无 | 应考、完成、缺考、未完成、已发布数量和发布状态 |
| `GET` | `/api/v1/exams/results/manage/plans/{planId}` | `exam:manage` | 查询指定计划的人员成绩明细 | 路径 `planId` | 每名应考人员的参与状态、答卷、成绩和发布状态 |
| `GET` | `/api/v1/exams/results` | 登录，按数据范围过滤 | 查询已发布考试结果与已结束考试的缺考记录 | 可选 `employeeId` | 结果列表，包含 `result_status=COMPLETED|ABSENT`；缺考记录的 `total_score=0` |
| `GET` | `/api/v1/exams/results/export` | `exam:manage`，按数据范围过滤 | 导出已发布成绩 | 可选 `planId`、`month=yyyy-MM`、`major` | Excel 文件 |

题库兼容 `SINGLE`、`MULTIPLE`、`TRUE_FALSE`、`SHORT`，但新建试卷仅允许前三种客观题；试卷总分必须等于 100 分。员工交卷后立即评分，管理员端可即时查看，员工端结果查询只返回 `published=true` 的成绩。`ExamScheduler` 每分钟先处理到期答卷，再将 `ends_at<=now()` 的已评分成绩统一标记为已发布。`dynamicAssembly=true` 时必须同时使用随机组卷；员工开始考试时，系统按员工档案 `major` 匹配题目 `tags`，无标签题作为公共题，每次答卷保存实际抽取题目。

动态试卷的题目集合以 `exam_attempt_question` 为准。开始考试后，查看答卷、保存答案、提交、自动评分和人工阅卷必须使用同一集合，不能重新按标签抽题。前端旧 `/exams` 地址只重定向到按角色可访问的拆分页面，不代表存在第二套考试 API。

## 账号管理

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/users` | `user:employee:manage` | 查询账号 | 可选 `role`；按当前账号可管理角色返回，站点负责人含 `station_ids`、`station_names` | 账号列表 |
| `POST` | `/api/v1/users` | 运营角色需 `user:ops-role:manage`，管理员需 `user:admin:manage` | 创建非员工账号 | `username`、`displayName`、`role=MENTOR|STATION_MANAGER|TRAINING_ADMIN|ADMIN`、`stationIds` | `id`、`temporaryPassword` |
| `PUT` | `/api/v1/users/{id}/enabled` | 员工账号需 `user:employee:manage`，运营角色需 `user:ops-role:manage`，管理员角色需 `user:admin:manage` | 启停账号 | `enabled` | 空 |
| `PUT` | `/api/v1/users/{id}/role` | `user:admin:manage` | 修改非当前账号角色 | 普通账号支持 `MENTOR|TRAINING_ADMIN|ADMIN|SUPER_ADMIN`；关联员工档案的账号角色固定为 `EMPLOYEE`，仅允许将历史异常角色恢复为 `EMPLOYEE` | 空 |
| `PUT` | `/api/v1/users/{id}/display-name` | `user:admin:manage` | 修改账号姓名 | `displayName`；关联员工档案的账号会同步更新员工姓名 | 空 |
| `PUT` | `/api/v1/users/{id}/username` | `user:admin:manage`（仅超级管理员） | 修改非超级管理员账号的用户名 | `username`，仅支持字母、数字、点、下划线、连字符；员工账号会同步更新工号并使原登录态失效 | 空 |
| `POST` | `/api/v1/users/{id}/reset-password` | 员工账号需 `user:employee:manage`，运营角色需 `user:ops-role:manage`，管理员角色需 `user:admin:manage` | 重置密码 | 路径 `id` | `temporaryPassword` |
| `PUT` | `/api/v1/users/{id}/stations` | `user:ops-role:manage` | 设置站点负责人服务站范围 | `stationIds` | 空 |

站点负责人必须至少绑定一个服务站。账号创建和重置密码返回临时密码，用户后续需要修改密码。

## 文件与审计

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/files/{id}` | 登录，按任务所属员工范围校验 | 下载成果附件 | 路径 `id` | 本地文件流或 OSS 5 分钟签名跳转 |
| `GET` | `/api/v1/audit-logs` | `audit:read` | 查询审计日志 | `limit`，范围 1 到 500，默认 100 | 审计日志列表 |

审计日志记录操作人、动作、目标类型、目标 ID、请求 ID、变更前后值和创建时间。

## 阶段 3 培养计划与任务模板

| 方法 | 路径 | 权限 | 用途 | 关键入参 | 关键返回 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/training-plans` | `task:manage` | 查询培养计划 | 可选 `keyword` | 计划列表、任务数量和已下发数量 |
| `GET` | `/api/v1/training-plans/summary` | `task:manage` | 查询计划库统计 | 无 | 计划、任务、已启用和已下发数量 |
| `POST` | `/api/v1/training-plans` | `task:manage` | 创建草稿培养计划 | `name`、`description` | 计划 ID |
| `PUT` | `/api/v1/training-plans/{id}` | `task:manage` | 编辑培养计划 | `name`、`description` | 空 |
| `POST` | `/api/v1/training-plans/{id}/copy` | `task:manage` | 复制计划、全部编排任务及附件 | 新计划 `name` | 新计划 ID |
| `DELETE` | `/api/v1/training-plans/{id}` | `task:manage` | 删除从未下发的计划 | 路径 `id` | 空 |
| `PUT` | `/api/v1/training-plans/{id}/enabled` | `task:manage` | 启停培养计划 | `enabled` | 空；启用前至少有一项任务 |
| `GET` | `/api/v1/training-plans/{id}/tasks` | `task:manage` | 查询计划任务编排 | 路径 `id` | 编排列表 |
| `POST` | `/api/v1/training-plans/{id}/tasks` | `task:manage` | 新建计划任务 | `title`、`description`、`requirements` | 计划任务 ID |
| `PUT` | `/api/v1/training-plans/{planId}/tasks/{taskId}` | `task:manage` | 编辑计划任务 | `title`、`description`、`requirements` | 空 |
| `DELETE` | `/api/v1/training-plans/{planId}/tasks/{taskId}` | `task:manage` | 删除未下发的计划任务 | 路径 `planId`、`taskId` | 空 |
| `PUT` | `/api/v1/training-plans/{id}/tasks/order` | `task:manage` | 调整计划任务顺序 | `items: [{id, sortOrder}]` | 空 |
| `GET` | `/api/v1/training-plans/{planId}/tasks/{taskId}/attachments` | `task:manage` | 查询计划任务附件 | 路径参数 | 附件列表 |
| `POST` | `/api/v1/training-plans/{planId}/tasks/{taskId}/attachments` | `task:manage` | 上传计划任务附件 | `multipart/form-data` 字段 `file` | 附件 ID |
| `POST` | `/api/v1/training-plans/{planId}/tasks/{taskId}/attachments/upload-ticket` | `task:manage` | 为计划任务附件申请 OSS 直传票据 | `originalName`、`contentType`、`size` | 上传票据 |
| `POST` | `/api/v1/training-plans/{planId}/tasks/{taskId}/attachments/upload-complete/{ticketId}` | `task:manage` | 校验 OSS 对象并创建计划附件 | 路径参数 | 附件 ID |
| `DELETE` | `/api/v1/training-plans/{planId}/tasks/{taskId}/attachments/{attachmentId}` | `task:manage` | 删除计划任务附件 | 路径参数 | 空 |
| `POST` | `/api/v1/tasks/dispatch-plan/preview` | `task:manage` | 预览计划任务下发 | 与正式下发相同 | 覆盖人数、任务数、复用数、截止时间和任务名称 |
| `POST` | `/api/v1/tasks/dispatch-plan` | `task:manage` | 从计划下发选定任务并生成附件快照 | `planId`、`planTaskIds`、可选 `taskTitle`、`deadlineMode`，以及可组合的 `batchId`、`businessUnitId`、`stationId` | `targetEmployees`、`createdTasks`、`createdAssignments` |

培养计划新建后默认为草稿，至少编排一项任务才允许启用。培养计划编排任务标题、任务说明、成果要求、附件和执行顺序，不包含人员与截止时间；计划任务应在“任务下发”页面按需下发。已产生下发记录的计划只能停用，不能删除；已下发的计划任务也不能删除，以保证历史可追溯。目标人员不支持逐人指定，可按 `batchId`（批次）、`businessUnitId`（所属板块）、`stationId`（服务站）组合筛选 `ACTIVE` 员工；同时填写多个条件时按交集匹配。机动车和城轨属于板块基础数据，不作为服务站处理。`taskTitle` 可选，留空时使用每个计划任务的名称，填写后作为本次下发任务的统一名称。`deadlineMode` 支持：`OFFSET`（`baseDate + offsetDays`）和 `ABSOLUTE`（`deadlineDate`）；均在当日 `23:59:59` 截止。下发结果关联 `training_plan_task_id`，同一计划任务和截止日期会复用任务，避免重复分配。计划附件在下发时复制为任务附件快照，后续模板附件调整不会影响已下发任务。
