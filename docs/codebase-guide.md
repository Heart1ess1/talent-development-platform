# 代码库导览与文件职责

本文面向新加入或接手本项目的同事，说明当前代码结构、主要运行链路，以及每个源码、配置、迁移和测试文件的大致职责。接口入参和完整权限口径请继续以 [API 接口约定](api-contract.md) 和 [权限矩阵](permissions-matrix.md) 为准。

## 1. 项目全景

这是一个“新员工一人一画像”培养管理平台。前端以 Vue 单页应用提供人员、课程、任务、评价、考试等页面；后端以 Spring Boot 同时提供 `/api/v1` REST API 和生产环境静态资源；MySQL 保存业务数据，Flyway 自动升级数据库；可选的 Windows 启动器用于发布包的一键启动和更新。

```text
浏览器 / Vue SPA
  ├─ Axios + JWT ────────────────────────> Spring Boot /api/v1
  ├─ 生产静态资源 / 公共图片 ─────────────> CDN → 公共 OSS Bucket
  └─ 私有附件签名上传 / 下载 ─────────────> 私有 OSS Bucket
                                             ├─ JdbcTemplate / MyBatis-Plus → MySQL
                                             ├─ Flyway → db/migration
                                             └─ 本地模式回退 → 本地磁盘

Windows 启动器 → Docker Compose(MySQL) + Java JAR + 浏览器
```

### 核心业务链路

1. 管理员维护批次、服务站、导师和员工；员工创建/导入时会同时创建 `EMPLOYEE` 账号。
2. 培训管理员安排课程、签到、培养计划和闯关任务；员工提交成果，具备审核权限的人员审核。
3. 考试管理员维护题库、客观题试卷和考试计划；员工交卷后立即自动计分，管理员即时可见，员工在整场考试结束后收到自动下发结果。
4. 评价方案定义评分项和权重；考试、任务等数据参与月评，系统可生成月度/季度汇总并在发布后锁定。
5. 所有登录态、访问范围和关键操作由安全与审计模块统一处理。

### 建议阅读顺序

1. 根目录 [README](../README.md) 与 `docs/requirements.md`，了解范围和启动方式。
2. `backend/src/main/resources/db/migration/V1__init.sql`，先认识核心表和基础数据。
3. `frontend/src/router.ts`、`frontend/src/layout/AppLayout.vue`，确认页面入口与菜单权限。
4. `backend/.../security/`、`auth/`，理解 JWT、角色、数据范围和强制改密。
5. 按业务阅读对应 Controller，再阅读它依赖的 Service、迁移和前端页面。

## 2. 顶层目录与运行方式

| 路径 | 作用 |
| --- | --- |
| `backend/` | Java 17 / Spring Boot 服务，整合权限、业务 API、数据库迁移和文件存储。 |
| `frontend/` | Vue 3 / TypeScript / Vite 单页应用。生产构建产物会被后端 Maven 构建打入 JAR。 |
| `launcher/` | .NET 8 Windows Forms 启动器源码，用于发布包启动、更新、停止平台。 |
| `deploy/aliyun/` | 阿里云 ECS 生产部署的 Compose、Nginx、安装、更新和验收脚本。 |
| `docs/` | 需求、接口、权限、任务与本文件等长期维护文档。 |
| `docker-compose.yml` | 本地 MySQL 8.4 服务与持久化卷。 |
| `CONTRIBUTING.md` | 分支、提交、PR 和发版协作规范。 |
| `README.md` | 仓库入口：功能、环境、启动、验证、发版与安全提示。 |

开发时先执行 `docker compose up -d`，在 `backend/` 运行 `mvn spring-boot:run`，在 `frontend/` 运行 `npm install` 和 `npm run dev`。Vite 将 `/api` 代理到 `8080`；打包时，`backend/pom.xml` 会把 `frontend/dist` 作为 `static` 资源带入后端 JAR。

## 3. 后端结构

后端代码位于 `backend/src/main/java/com/talent/platform/`。多数业务 Controller 直接使用 `JdbcTemplate` 编写 SQL；账号实体和 Mapper 使用 MyBatis-Plus。所有 JSON 成功响应都由 `ApiResponse` 包装，异常由全局处理器转为统一响应。

### 3.1 启动、通用与认证

| 文件 | 职责 |
| --- | --- |
| `TalentPlatformApplication.java` | Spring Boot 主入口；启用配置属性扫描。 |
| `common/ApiResponse.java` | 统一 API 响应结构与成功/失败工厂方法，包含请求 ID。 |
| `common/BusinessException.java` | 携带业务 HTTP 状态码的运行时异常。 |
| `common/GlobalExceptionHandler.java` | 将校验、权限、业务和未捕获异常转换为统一 API 错误响应。 |
| `common/PageResult.java` | 分页记录、总数、页码和页大小的数据结构。 |
| `common/SpaController.java` | 将非 API 的前端路由转发到 `index.html`，支持 SPA 刷新直达页面。 |
| `auth/AuthController.java` | 登录、当前用户查询和修改密码；登录后签发 JWT，改密后提升安全版本并返回新令牌。 |
| `auth/BootstrapAdmin.java` | 应用启动时创建超级管理员，或在演示模式下幂等写入演示账号和员工档案。 |

### 3.2 安全、权限、审计与账号持久化

| 文件 | 职责 |
| --- | --- |
| `persistence/SysUser.java` | `sys_user` 表的 MyBatis-Plus 实体，保存账号、角色、启停、版本和安全版本。 |
| `persistence/SysUserMapper.java` | `SysUser` 的 MyBatis-Plus Mapper。 |
| `security/CurrentUser.java` | JWT 认证后的当前用户不可变视图，含权限集合和数据范围。 |
| `security/Permissions.java` | 统一声明权限点字符串常量。 |
| `security/PermissionService.java` | 按角色计算权限和 `SELF`/`MENTORED`/`STATION`/`ALL` 数据范围；提供单员工和 SQL 过滤校验。 |
| `security/SecurityUtils.java` | 从 Spring Security 上下文取得当前 `CurrentUser`。 |
| `security/JwtService.java` | 创建、解析和校验 JWT；利用账号安全版本使旧令牌失效。 |
| `security/JwtFilter.java` | 从 Bearer Token 恢复认证，并验证当前账号状态和安全版本。 |
| `security/PasswordChangeFilter.java` | 对被标记为必须改密的用户限制可访问接口。 |
| `security/SecurityConfig.java` | 配置 BCrypt、CORS、无状态安全链、公开路由、JWT/改密过滤器及未认证响应。 |
| `security/AuditService.java` | 记录关键业务操作的操作人、目标、变更前后数据和请求 ID。 |
| `security/AuditController.java` | 提供审计日志查询接口。 |
| `user/UserController.java` | 非员工账号的创建、启停、改角色、改姓名/用户名、重置密码与站点负责人范围配置。 |

### 3.3 人员、基础资料、课程与导入

| 文件 | 职责 |
| --- | --- |
| `master/MasterDataController.java` | 培养批次、所属板块、服务站点和导师列表的查询及基础数据创建。 |
| `employee/EmployeeController.java` | 人员详情、新建、修改与批量设置技术/技能导师；创建时同步创建员工账号，直接调站时同步写入历史。 |
| `employee/EmployeeProfileController.java` | 员工本人查看与维护个人资料；限制修改工作分配类字段。 |
| `employee/EmployeeDirectoryController.java` | 面向目录场景的多条件人员查询和 Excel 导出，并应用数据范围过滤。 |
| `employee/EmployeeDirectoryExportRow.java` | 人员目录 Excel 导出的列定义与表头映射。 |
| `importer/ImportController.java` | 员工与签到 Excel 模板下载、整批校验和导入；任一行错误时不写入。 |
| `importer/EmployeeImportRow.java` | 员工导入 Excel 行模型和列映射。 |
| `importer/AttendanceImportRow.java` | 签到导入 Excel 行模型和列映射。 |
| `station/StationChangeRequestController.java` | 员工服务站变更申请、本人记录、审批统计与多条件查询、管理员安全审批，以及按人员数据范围查询已生效历史。 |
| `movement/LocationReportController.java` | 员工自主位置报备、本人轨迹，以及导师和管理角色按人员数据范围查询人员流动、当前位置和统计。 |
| `course/CourseController.java` | 课程生命周期、课件元数据与原文件下载封禁、场次和人员安排、签到码自助签到、人工补录、统计与多条件查询。 |
| `course/CourseMaterialLearningController.java` | 员工课件清单、管理端学习统计、安全预览会话、学习心跳与账号级页面访问控制。 |
| `course/CourseMaterialPreviewService.java` | 将 Word、PDF、PPT、OFD 或图片逐页渲染为带姓名/工号水印的 PNG；Office 使用 LibreOffice 转 PDF，OFD 使用 OFDRW 转图片，原课件不进入浏览器。 |

### 3.4 任务、文件与培养计划

| 文件 | 职责 |
| --- | --- |
| `task/TaskController.java` | 任务 CRUD、手动/培养计划下发、下发预览、任务附件、分配进度、员工带附件提交、审核与提交历史；负责访问范围与目标人员校验。 |
| `task/TaskAttachmentService.java` | 任务资料上传、列表、删除、共享存储引用，以及计划任务附件在下发时生成独立快照。 |
| `task/TaskStatusService.java` | 在启动、任务变动后计算最近截止时间并安排定时任务；把逾期未提交分配固化为 `OVERDUE` 和 0 分。 |
| `task/TaskSchedulingConfiguration.java` | 提供任务状态服务使用的 Spring `TaskScheduler`。 |
| `training/TrainingPlanController.java` | 培养计划统计、草稿创建、编辑、复制、安全启停/删除，以及计划任务编排、使用情况与完整排序校验。 |
| `storage/FileStorageService.java` | 私有文件对象存储抽象：保存、读取、删除、OSS 直传票据校验及短时签名下载。 |
| `storage/LocalFileStorageService.java` | `STORAGE_TYPE=local` 时按日期和 UUID 写入本地目录，并防止路径穿越。 |
| `storage/OssFileStorageService.java` | `STORAGE_TYPE=oss` 时使用私有 Bucket 和 ECS RAM Role，提供限制大小的 POST Policy、临时对象提交、GET 预签名 URL 和内部读取。 |
| `storage/UploadTicketService.java` | 创建绑定用户、用途和业务对象的 15 分钟上传票据，校验并单次消费，定时清理过期对象。 |
| `storage/StorageTransferController.java` | 向前端返回本环境是否启用直传和签名下载。 |
| `storage/PublicAssetStorageService.java` | 头像等公共资源的独立存储抽象。 |
| `storage/LocalPublicAssetStorageService.java` | 本地模式复用本地存储读取公共图片。 |
| `storage/OssPublicAssetStorageService.java` | OSS 模式把公共图片写入公共资源 Bucket，并在配置后返回 CDN URL。 |
| `storage/FileController.java` | 按任务数据范围授权后返回本地文件，或 302 到 5 分钟 OSS 签名下载地址。 |

### 3.5 评价、考试和概览

| 文件 | 职责 |
| --- | --- |
| `dashboard/DashboardController.java` | 按角色生成两套概览模型：员工个人待办、学习日程、完成记录、季度评分和导师反馈；管理侧职责待办、四条业务进度、近期安排和风险员工，全部沿用人员数据范围。 |
| `evaluation/EvaluationController.java` | 评价模板库、模板应用、工作台待办、月度方案、评分项提交/覆盖、加扣分、月度/季度汇总生成、发布与重开的 API。 |
| `evaluation/EvaluationAssignmentController.java` | 评分任务生成、任务概览、全员/批次/板块范围规则、候选评分人、本人任务和兼容批量分配 API。 |
| `evaluation/EvaluationAssignmentService.java` | 生成员工级人工评分任务，并把板块、批次、全员范围规则按优先级展开为有效评分人，汇总个人提交进度与正式平均分。 |
| `evaluation/EvaluationService.java` | 评价核心计算：匹配月份方案，把考试/任务来源折算到配置满分，聚合人工评分、覆盖与加扣分，并写入可追溯的月度和季度快照。 |
| `evaluation/EvaluationRules.java` | 纯规则函数：校验月度评分项权重、季度权重，计算多人过程/完成平均分及限定在 0–100 的最终分数。 |
| `evaluation/EvaluationScheduler.java` | 每月 1 日 02:00 自动生成上月月评；每季度首月 03:00 自动生成上季度汇总。 |
| `exam/ExamController.java` | 多题库与题目维护、客观题手动/随机/一人一卷组卷、考试计划与分配、考生作答、防作弊事件、管理端即时成绩和员工端延迟可见结果。 |
| `exam/ExamScoringService.java` | 自动阅卷服务；统一读取静态或动态答卷题目，处理超时交卷，并在整场考试结束后批量标记成绩可见。 |
| `exam/ExamScheduler.java` | 每分钟先提交到期答卷，再自动发布已结束考试的客观题成绩。 |
| `exam/QuestionImportRow.java` | 题库 Excel 导入行模型和字段映射。 |
| `exam/ResultExportRow.java` | 已发布考试成绩 Excel 导出的列定义。 |

## 4. 后端资源、构建与数据库

| 文件 | 职责 |
| --- | --- |
| `backend/pom.xml` | Maven 构建、Java 17、Spring Boot、MyBatis-Plus、Flyway、JWT、EasyExcel、OSS 等依赖；构建时并入前端静态资源。 |
| `backend/src/main/resources/application.yml` | 数据库、上传大小、时区、JWT、初始管理员、演示账号、存储策略和 CORS 的环境变量默认值。 |
| `backend/src/main/resources/templates/question-bank-template.xlsx` | 题库正式导入模板；包含三类客观题题型和专业标签下拉、分值校验、填写指南及示例，下载接口直接返回该受版本控制的模板。 |

### Flyway 迁移（按版本只增不改）

| 文件 | 职责 |
| --- | --- |
| `db/migration/V1__init.sql` | 初始账号、批次、服务站、员工、课程、签到、任务、附件与操作日志表，并写入默认 `2026届` 批次。 |
| `db/migration/V2__permissions_evaluation_exam.sql` | 加入账号安全版本、站点负责人范围、课程报名、审计扩展、综合评价和考试全套表。 |
| `db/migration/V3__exam_question_bank_and_random_paper.sql` | 扩展随机组卷相关字段，并为题库题型/启用状态建索引。 |
| `db/migration/V4__evaluation_components.sql` | 扩展评价方案评分项、评分覆盖和汇总快照字段，并迁移既有评价数据。 |
| `db/migration/V5__employee_profile_email.sql` | 扩展员工个人资料与邮箱字段。 |
| `db/migration/V6__training_plan_templates.sql` | 新增培养计划、任务模板及计划任务关联，并让任务可关联模板来源。 |
| `db/migration/V7__direct_training_plan_tasks.sql` | 调整为计划任务直接作为下达来源，迁移关联、索引和唯一约束。 |
| `db/migration/V8__use_plan_task_title_for_dispatched_tasks.sql` | 迁移既有下达任务标题，使其使用计划任务标题。 |
| `db/migration/V9__deduplicate_exam_proctor_events.sql` | 为防作弊事件增加客户端事件键和唯一约束，避免重复上报累计。 |
| `db/migration/V10__exam_plan_target_scopes.sql` | 增加考试计划批次、板块多选目标范围。 |
| `db/migration/V11__add_employee_extra_fields.sql` | 增加政治面貌、兴趣爱好、特长和身份证号码字段。 |
| `db/migration/V12__create_station_change_request.sql` | 增加服务站变更申请、审批状态、审核人与查询索引。 |
| `db/migration/V13__dynamic_exam_labels.sql` | 增加题目标签、动态试卷规则和每次答卷实际抽题表。 |
| `db/migration/V21__exam_question_banks.sql` | 增加独立题库实体，将历史题目归入默认题库，并为随机组卷规则增加题库范围。 |
| `db/migration/V14__station_change_reviewed_at.sql` | 增加服务站变更审批生效时间并为人员历史查询建立索引。 |
| `db/migration/V15__employee_organization_and_dual_mentors.sql` | 增加所属板块主数据、员工板块和技能导师关联，并允许管理员直接取消站点分配时留存历史。 |
| `db/migration/V16__move_vehicle_categories_to_business_units.sql` | 将车型分类口径迁移为所属板块主数据。 |
| `db/migration/V17__add_user_avatar.sql` | 增加账号头像存储键和公开随机令牌字段。 |
| `db/migration/V18__create_employee_location_report.sql` | 增加员工位置报备、时间轨迹及常用查询索引。 |
| `db/migration/V19__course_materials.sql` | 增加课程课件元数据及课程、上传人关联。 |
| `db/migration/V20__task_attachments.sql` | 增加计划任务和已下发任务的附件元数据、快照来源及访问索引。 |
| `db/migration/V22__evaluation_template_library.sql` | 增加独立评价模板库、评分项满分和方案模板来源，保留既有方案的百分制默认口径。 |
| `db/migration/V25__evaluation_source_weights.sql` | 增加任务/考试内部权重、站点汇总模式、多导师/多站点评分作用域及员工月度站点权重。 |
| `db/migration/V26__evaluation_rating_tasks.sql` | 增加人工评分任务与有效评分人表，并把历史月度人工评分回填为可查询任务。 |
| `db/migration/V27__evaluation_group_reviewer_rules.sql` | 增加按月份、评分项和全员/批次/板块维护的评分人范围规则及成员表，并记录员工任务评分人的分配来源。 |
| `db/migration/V23__course_material_learning.sql` | 增加课件预览会话、员工学习次数和累计学习时长记录。 |
| `db/migration/V24__object_upload_ticket.sql` | 增加 OSS 客户端直传票据、用途/归属绑定、有效期和单次消费状态。 |

## 5. 前端结构

前端源码位于 `frontend/src/`。组件统一使用 Vue 3 Composition API（`<script setup>`），HTTP 访问经 `api.ts`，认证状态经 Pinia，路由元数据和菜单同时做前端可见性控制；最终访问权限仍由后端验证。

### 应用骨架、请求与工具

| 文件 | 职责 |
| --- | --- |
| `main.ts` | 创建 Vue 应用，注册 Pinia、Vue Router、Element Plus、全局样式并挂载。 |
| `App.vue` | 应用根组件，仅渲染路由出口。 |
| `router.ts` | 声明登录页、应用布局和所有业务路由；路由守卫处理登录、强制改密、员工访问人员信息页时的跳转和权限拦截。旧 `/employees` 地址重定向到统一人员信息页。 |
| `api.ts` | Axios 实例、JWT 与请求 ID 注入、统一错误提示、401 清理本地登录态。 |
| `env.d.ts` | Vite/Vue 的 TypeScript 环境类型声明。 |
| `styles.css` | 全局色彩、页面间距、卡片、表单、响应式等基础样式。 |
| `stores/auth.ts` | Pinia 登录态：登录、退出、刷新当前用户、改密及权限判断。 |
| `utils/progress.ts` | 任务完成率与任务状态中文标签等可复用纯函数。 |
| `utils/progress.test.ts` | `progress.ts` 的 Vitest 单元测试。 |
| `layout/AppLayout.vue` | 登录后的应用框架：桌面侧栏、移动导航、按权限过滤菜单、顶部用户信息与退出。 |

### 业务页面

| 文件 | 职责 |
| --- | --- |
| `views/LoginView.vue` | 与业务后台统一设计语言的响应式登录门户，包含品牌能力说明、账号记忆、大写锁定提示、前端校验、错误反馈和登录后路由跳转。 |
| `views/DashboardView.vue` | 根据 `audience` 呈现员工个人学习主页或管理培养运营工作台，并提供课程、考试、任务和评价模块的可执行跳转。 |
| `views/EmployeeDirectoryView.vue` | “人员管理”模块下的人员台账工作台：管理概览、状态页签、响应式筛选、单行人员列表、证件照与完整档案、新增编辑、双导师批量设置、基础数据、导入导出和服务站变更轨迹。 |
| `views/LocationReportsView.vue` | 按角色呈现员工本人位置报备或管理侧人员流动看板，支持当前位置、筛选、统计和单人历史抽屉。 |
| `views/ProfileView.vue` | 当前用户上传头像、修改密码；员工按“证件照”语义维护照片，并可维护本人资料、提交调站申请及查看审批记录。 |
| `views/StationChangeReviewView.vue` | 管理端调站审批工作台：待办统计、平均等待、组合筛选、人员与调站背景、详情历史，以及带业务提示的通过/拒绝决策。 |
| `views/CourseCatalogView.vue` | 管理端课程库：课程统计、搜索、创建编辑、启停、安全删除及课件资料入口。 |
| `views/CourseSessionsView.vue` | 管理端场次安排：培训时间、地点、学时、签到窗口、签到码与参训人员维护。 |
| `views/CourseAttendanceView.vue` | 按角色展示管理端签到工作台或员工签到记录，支持统计、筛选、补录和导入。 |
| `views/MyCoursesView.vue` | 员工课程日程、签到入口和本人已安排课程的课件访问。 |
| `components/CourseMaterialsPanel.vue` | 管理课程课件上传、删除和安全预览，不再提供下载按钮。 |
| `components/CourseMaterialPreview.vue` | 创建账号绑定的预览会话、加载逐页水印图、定时上报学习心跳并在关闭时结算。 |
| `views/CourseLearningView.vue` | 员工课件学习页，仅展示可学习课件及已学习/未学习状态。 |
| `views/CoursewareManagementView.vue` | 管理端课件概览和员工学习明细，展示人数、次数和学习时长。 |
| `utils/course.ts` | 课程和场次类型、场次状态、签到来源、日期与文件大小显示规则。 |
| `styles/courses.css` | 课程模块共享的桌面与移动端页面规范。 |
| `views/TrainingPlanManagementView.vue` | 培养计划库工作台：统计、搜索、状态筛选、草稿创建、编辑、复制、启停和安全删除。 |
| `views/TrainingPlanTasksView.vue` | 计划任务编排工作台：计划切换、任务新增编辑、附件维护、拖动/按钮排序、下发使用状态和启用校验。 |
| `styles/training-plans.css` | 计划管理和任务编排共享的响应式页面布局与视觉规范。 |
| `utils/trainingPlan.ts` | 培养计划类型、启用判断、业务状态和日期显示规则。 |
| `components/TaskAttachmentsPanel.vue` | 任务附件文件名展示、上传、删除、下载，以及 PDF、图片、文本和 DOCX 安全预览。 |
| `views/TasksView.vue` | 管理侧任务下发和任务跟踪、员工侧我的任务、任务附件、提交/重提、审核、进度明细和筛选。 |
| `storageTransfer.ts` | 查询存储能力，在 OSS 模式执行受 Policy 限制的表单直传和完成确认，在本地模式回退 multipart 上传。 |
| `views/evaluation/EvaluationWorkbenchView.vue` | 按月份展示方案覆盖、发布进度、缺失汇总和跨任务/考试/人工评价的待办入口。 |
| `views/evaluation/EvaluationAssignmentsView.vue` | 管理员先选择导师、站点或培训任务，再按全员、批次或板块统一配置多名评分人，并查看自动展开后的覆盖进度。 |
| `views/evaluation/EvaluationAssignmentDetailView.vue` | 只读追踪单项员工任务的批次/板块匹配依据、评分人、提交时间、个人分数和平均分。 |
| `views/evaluation/MyEvaluationTasksView.vue` | 导师、站点负责人和培训管理员查看只分配给本人的评分任务及团队提交进度。 |
| `views/evaluation/EvaluationMonthlyView.vue` | 按员工和月份核对自动来源、提交职责内人工评分、管理员核定及加扣分，并预览综合分。 |
| `views/evaluation/EvaluationTemplatesView.vue` | 管理独立评价模板，将模板应用到批次月份形成方案草稿，并维护发布和历史版本。 |
| `views/evaluation/EvaluationResultsView.vue` | 查询月度/季度结果与分项快照，生成、发布或重开汇总；员工侧只显示本人已发布结果。 |
| `evaluation/model.ts` | 综合评价的评分项定义、初始模板、状态和显示格式共享模型。 |
| `styles/evaluation-center.css` | 综合评价工作台、任务编排、月度评分、模板和结果页面共享的卡片、表格、快照及响应式视觉规范。 |
| `styles/dashboard.css` | 员工主页与管理操作台共用的指标卡、待办、业务进度、日程、风险人员及响应式视觉规范。 |
| `views/exams/ExamQuestionBankView.vue` | 多题库目录、题目新增编辑、标签、启停、安全删除和指定题库 Excel 导入。 |
| `views/exams/ExamPapersView.vue` | 分步抽屉式手动/随机/一人一卷组卷、题库范围、试卷详情和安全删除。 |
| `views/exams/ExamPlansView.vue` | 考试时间、试卷、批次/板块范围、参考员工选择、草稿删除及发布。 |
| `views/exams/MyExamsView.vue` | 员工考试列表、客观题作答保存、计时和防作弊事件上报；仅对历史简答题答卷保留兼容显示。 |
| `views/exams/ExamResultsView.vue` | 考试完成情况、管理员即时成绩、员工延迟可见状态、历史简答题兼容阅卷和 Excel 导出；不再提供人工成绩发布。 |
| `styles/exam-center.css` | 题库、试卷、考试计划和成绩管理共享的页面头、概览卡、工作区与响应式视觉规范。 |
| `views/exams/examUi.ts` | 考试状态、题型和日期显示的共享前端工具。 |
| `views/exams/examProctor.ts` | 考试服务端时钟校准、截止时间倒计时格式化、防作弊事件 ID 兼容生成和 `keepalive` 可靠上报；`MyExamsView.vue` 负责考前诚信确认、全屏准入、状态轮询和到时自动交卷。 |
| `views/UsersView.vue` | 账号列表、创建、启停、重置密码、改角色/账号名/显示名及站点负责人范围配置。 |
| `utils/avatar.ts` | 头像公开地址与姓名末字默认头像的统一前端规则。 |
| `utils/role.ts` | 英文角色代码到中文界面文案的统一映射；权限判断仍使用原始代码。 |

### 前端构建配置

| 文件 | 职责 |
| --- | --- |
| `frontend/package.json` | 前端依赖和 `dev`、`build`、`test` 脚本。 |
| `frontend/package-lock.json` | npm 精确依赖锁定文件；新协作者使用 `npm ci` 安装一致版本。 |
| `frontend/pnpm-workspace.yaml` | pnpm 工作区配置。 |
| `frontend/index.html` | Vite HTML 入口及应用挂载节点。 |
| `frontend/tsconfig.json` | TypeScript 根项目引用配置。 |
| `frontend/tsconfig.app.json` | 浏览器端 TypeScript 编译选项。 |
| `frontend/tsconfig.node.json` | Vite 配置等 Node 侧 TypeScript 编译选项。 |
| `frontend/vite.config.ts` | Vue 插件、`@`→`src` 别名、5173 端口、`/api` 本地代理，以及生产 CDN `VITE_ASSET_BASE`。 |

## 6. 测试文件

后端测试位于 `backend/src/test/java/com/talent/platform/`，主要覆盖纯规则和 Controller/服务关键分支。执行 `cd backend; mvn test`；前端工具函数执行 `cd frontend; npm run test`。

| 文件 | 覆盖内容 |
| --- | --- |
| `employee/EmployeeDirectoryControllerTest.java` | 人员目录筛选、数据范围或导出相关行为。 |
| `employee/EmployeeProfileControllerTest.java` | 员工个人资料读取、编辑边界与权限限制。 |
| `movement/LocationReportControllerTest.java` | 首次位置起点、轨迹时间顺序、员工管理入口隔离和导师数据范围。 |
| `evaluation/EvaluationAssignmentControllerTest.java` | 本人任务按当前账号过滤、月份任务生成、多人分配和任务范围规则请求转发。 |
| `evaluation/EvaluationAssignmentServiceTest.java` | 校验全员、批次和板块范围对员工任务的匹配边界。 |
| `evaluation/EvaluationRulesTest.java` | 评价权重、多人完成平均分和最终得分计算。 |
| `exam/ExamScoringServiceTest.java` | 客观题/多选题答案比对和阅卷逻辑。 |
| `security/JwtServiceTest.java` | JWT 创建、解析和失效相关行为。 |
| `security/PermissionServiceTest.java` | 角色权限集和数据范围过滤规则。 |
| `security/SecurityUtilsTest.java` | 当前登录用户读取工具。 |
| `station/StationChangeRequestControllerTest.java` | 调站历史、拒绝原因、过期申请并发保护和审批统计。 |
| `task/TaskControllerTest.java` | 任务接口的主要权限与业务分支。 |
| `task/TaskStatusServiceTest.java` | 逾期任务刷新与截止时间调度计算。 |
| `training/TrainingPlanControllerTest.java` | 培养计划和计划任务的管理接口。 |
| `user/AvatarControllerTest.java` | 头像图片真实性校验、替换和旧文件清理。 |
| `storage/UploadTicketServiceTest.java` | OSS 上传票据签发、对象校验、单次消费和跨用户拒绝。 |
| `user/UserControllerTest.java` | 账号管理、角色和站点范围的规则。 |

## 7. Windows 启动器与部署辅助文件

| 文件 | 职责 |
| --- | --- |
| `launcher/Program.cs` | Windows Forms 启动器主体：单实例互斥、Docker Desktop 检查/启动、MySQL 启停、Java JAR 生命周期、健康检查、浏览器打开和基于源码的 Docker 构建更新。 |
| `launcher/TalentPlatformLauncher.csproj` | .NET 8 Windows Forms 单文件、自包含、`win-x64` 发布配置。 |
| `launcher/app.manifest` | Windows 应用清单（系统兼容性与运行方式）。 |
| `docker-compose.yml` | MySQL 8.4 容器、默认开发账号、端口、健康检查和命名卷。 |
| `deploy/aliyun/docker-compose.yml` | ECS 生产容器拓扑；只有 Nginx 暴露 80/443 端口，应用和 MySQL 留在内部网络；MySQL、上传回退目录和预览缓存绑定到 100 GiB 数据盘的 `/data/talent-platform/`。 |
| `deploy/aliyun/nginx.conf` | ECS 的静态入口和反向代理配置。 |
| `deploy/aliyun/build-production.ps1` | 使用 CDN AssetBase 构建生产前端，执行前后端测试并输出 JAR 哈希。 |
| `deploy/aliyun/sync-public-assets.sh` | 通过 ECS RAM Role 同步内容哈希静态资源到公共 Bucket。 |
| `deploy/aliyun/migrate-local-files.sh` | 只复制历史本地文件到私有/公共 Bucket，保留本地回退源。 |
| `deploy/aliyun/install-runtime.sh` | 在 Alibaba Cloud Linux 4 安装并启用 Docker 与 Compose。 |
| `deploy/aliyun/docker-talent-data.conf` | 将 Docker 启动顺序绑定到 `/data` 挂载，防止数据盘异常时误写系统盘。 |
| `deploy/aliyun/bootstrap.sh` | 创建受限权限的生产环境变量、拉取镜像并首次启动。 |
| `deploy/aliyun/update-app.sh` | 安装新 JAR、重建应用容器并等待健康检查。 |
| `deploy/aliyun/backup-mysql.sh` | 将 MySQL 逻辑备份写入 100 GiB 数据盘并执行压缩、哈希与 14 天保留期管理。 |
| `deploy/aliyun/talent-platform-backup.service` / `.timer` | 每日触发数据库逻辑备份并在停机错过后补跑。 |
| `deploy/aliyun/verify.sh` | 检查容器、健康状态、Flyway 迁移和用户数据；首次改密前可额外验证初始登录。 |

`launcher/bin/`、`launcher/obj/`、`backend/target/`、`frontend/dist/` 等为构建产物，不是需要维护的源代码；请勿将它们作为业务修改入口。

## 8. 接手时的关键约束

- 权限校验必须同时考虑“功能权限”和“员工数据范围”；不要仅依赖前端菜单隐藏。
- JWT 通过 `security_version` 支持账号启停、角色调整、改密后的旧令牌失效；涉及账号安全的修改应保持这一机制。
- 已合并的 Flyway 迁移不能修改；数据库变更新增 `Vx__description.sql`。
- 已发布的月度评价会锁定；修改评价计算或评分项时先检查 `EvaluationService` 与相关迁移。
- 文件下载必须继续经任务所属员工范围校验；本地文件实现已专门防范路径穿越。
- 头像和员工证件照是同一份账号媒体资产；公开读取只使用随机 `avatar_token`，不得改为可枚举用户 ID。
- 位置报备记录临时实际地点，不更新 `employee.station_id`；正式归属站点变化继续走 `station_change_request`。位置历史按发生时间连续追加，不提供管理侧改写。
- 发布时，前端必须先构建，后端 Maven 才会将 `frontend/dist` 一并打入 JAR。

## 9. 近期结构调整与维护决策

### 9.1 人员功能为何统一

原 `EmployeesView.vue` 负责新增、编辑和导入，`EmployeeDirectoryView.vue` 负责查询与档案，两者围绕同一员工实体形成重复入口。当前只保留 `EmployeeDirectoryView.vue` 作为统一人员台账工作台，并承担查询、完整档案、新增编辑、基础数据、批量导师设置、导入导出和服务站历史；导航上与“人员流动”“调站审批”共同归属“人员管理”一级模块。

后端没有删除 `EmployeeController`。`/api/v1/employees` 除了承载人员写操作，还被课程、任务和评价页面用于人员选择；`/api/v1/employee-directory` 则是为统一工作台和 Excel 导出准备的扩展只读模型。维护时应保持两类接口的数据范围规则一致，但不要为了“页面去重”强行合并成一个超大接口。

服务站历史以 `station_change_request` 为唯一轨迹来源。员工申请走待审、通过或拒绝流程；管理员在人员编辑中直接调整站点时写入一条已生效记录。新增任何站点修改入口时，也必须同步维护这条历史链。

### 9.2 考试页面为何拆分

原 `ExamsView.vue` 同时包含题库、组卷、计划、作答、阅卷和成绩，权限边界与页面状态互相耦合。当前按业务职责拆为 `views/exams/` 下的五个页面，共用 `examUi.ts` 的显示规则，后端仍由 `ExamController` 统一维护事务和考试状态。

动态“一人一卷”不能只在开始考试时临时返回题目。实际抽取结果必须写入 `exam_attempt_question`，后续保存答案、恢复答卷、自动评分和人工阅卷都读取同一份题目集合，否则刷新页面或重新评分时可能出现题目漂移。

### 9.3 协作者分支如何整合

- `dev-wanben` 提供员工扩展资料、导入和服务站变更能力。整合时重新编排为 V11-V12，并补齐现有人员字段、数据范围和审批并发校验。
- `dzw_exam_TuoZhan` 提供题目标签、动态组卷和成绩导出。整合时适配拆分后的考试页面，将迁移编排为 V13，并补齐动态答卷持久化和评分测试。
- 这两个原始分支已经进入当前集成结果。它们的原提交不一定出现在最终分支祖先链中，因为采用了择取、冲突处理和后续修正；不要再次直接 merge 原分支。

### 9.4 修改后的同步清单

- Controller 路径、权限、入参或返回字段变化：同步 `docs/api-contract.md`。
- 新增、删除或拆分模块、页面、迁移和测试文件：同步本文件。
- 用户可见功能、启动方式、测试结果、发布要求或协作基线变化：同步根目录 `README.md`。
- 权限点或角色范围变化：同步 `docs/permissions-matrix.md`。
- 需求边界或任务状态变化：同步 `docs/requirements.md` 和 `docs/task-board.md`。
