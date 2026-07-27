# 代码库导览与文件职责

本文面向新加入或接手本项目的同事，说明当前代码结构、主要运行链路，以及每个源码、配置、迁移和测试文件的大致职责。接口入参和完整权限口径请继续以 [API 接口约定](api-contract.md) 和 [权限矩阵](permissions-matrix.md) 为准。

## 1. 项目全景

这是一个“新员工一人一画像”培养管理平台。前端以 Vue 单页应用提供人员、课程、任务、评价、考试等页面；后端以 Spring Boot 同时提供 `/api/v1` REST API 和生产环境静态资源；MySQL 保存业务数据，Flyway 自动升级数据库；可选的 Windows 启动器用于发布包的一键启动和更新。

```text
浏览器 / Vue SPA
  ├─ Axios + JWT ────────────────────────> Spring Boot /api/v1
  └─ 生产环境静态资源 <───────────────────┘
                                             ├─ JdbcTemplate / MyBatis-Plus → MySQL
                                             ├─ Flyway → db/migration
                                             └─ FileStorageService → 本地磁盘或阿里云 OSS

Windows 启动器 → Docker Compose(MySQL) + Java JAR + 浏览器
```

### 核心业务链路

1. 管理员维护批次、服务站、导师和员工；员工创建/导入时会同时创建 `EMPLOYEE` 账号。
2. 培训管理员安排课程、签到、培养计划和闯关任务；员工提交成果，具备审核权限的人员审核。
3. 考试管理员维护题库、试卷、考试计划；员工答题，客观题自动计分、主观题人工评分并发布结果。
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
| `master/MasterDataController.java` | 培养批次、服务站和导师列表的查询及基础数据创建。 |
| `employee/EmployeeController.java` | 员工台账分页、详情、新建、修改与批量绑定导师；创建时同步创建员工账号。 |
| `employee/EmployeeProfileController.java` | 员工本人查看与维护个人资料；限制修改工作分配类字段。 |
| `employee/EmployeeDirectoryController.java` | 面向目录场景的多条件人员查询和 Excel 导出，并应用数据范围过滤。 |
| `employee/EmployeeDirectoryExportRow.java` | 人员目录 Excel 导出的列定义与表头映射。 |
| `importer/ImportController.java` | 员工与签到 Excel 模板下载、整批校验和导入；任一行错误时不写入。 |
| `importer/EmployeeImportRow.java` | 员工导入 Excel 行模型和列映射。 |
| `importer/AttendanceImportRow.java` | 签到导入 Excel 行模型和列映射。 |
| `station/StationChangeRequestController.java` | 员工服务站变更申请、本人记录查询、管理员审批及已通过历史查询。 |
| `course/CourseController.java` | 课程、场次、员工安排、签到码自助签到、人工补录和签到记录查询。 |

### 3.4 任务、文件与培养计划

| 文件 | 职责 |
| --- | --- |
| `task/TaskController.java` | 任务 CRUD、手动/培养计划下达、分配进度、员工带附件提交、审核与提交历史；负责目标人员和文件类型/数量校验。 |
| `task/TaskStatusService.java` | 在启动、任务变动后计算最近截止时间并安排定时任务；把逾期未提交分配固化为 `OVERDUE` 和 0 分。 |
| `task/TaskSchedulingConfiguration.java` | 提供任务状态服务使用的 Spring `TaskScheduler`。 |
| `training/TrainingPlanController.java` | 培养计划及其任务模板的创建、编辑、启停、排序、删除与查询。 |
| `storage/FileStorageService.java` | 文件对象存储抽象：保存、读取、删除及返回存储键/大小/内容类型。 |
| `storage/LocalFileStorageService.java` | `STORAGE_TYPE=local` 时按日期和 UUID 写入本地目录，并防止路径穿越。 |
| `storage/OssFileStorageService.java` | `STORAGE_TYPE=oss` 时通过阿里云 OSS 保存、读取和删除文件。 |
| `storage/FileController.java` | 按任务数据范围授权后下载已上传附件。 |

### 3.5 评价、考试和概览

| 文件 | 职责 |
| --- | --- |
| `dashboard/DashboardController.java` | 汇总当前数据范围内的员工数、任务完成、签到、成绩与分布数据，供概览页展示。 |
| `evaluation/EvaluationController.java` | 评分方案、月度评分项提交/覆盖、加扣分、月度/季度汇总生成、发布与重开的 API。 |
| `evaluation/EvaluationService.java` | 评价核心计算：匹配适用方案、聚合考试/任务/人工评分、处理覆盖与加扣分、写入月度和季度汇总、锁定规则。 |
| `evaluation/EvaluationRules.java` | 纯规则函数：校验月度评分项权重、季度权重，并计算限定在 0–100 的最终分数。 |
| `evaluation/EvaluationScheduler.java` | 每月 1 日 02:00 自动生成上月月评；每季度首月 03:00 自动生成上季度汇总。 |
| `exam/ExamController.java` | 题库标签与导入、手动/随机/一人一卷组卷、考试计划与分配、考生作答、防作弊事件、阅卷、结果发布与 Excel 导出。 |
| `exam/ExamScoringService.java` | 自动阅卷服务；统一读取静态试卷题目或动态答卷题目，客观题比对答案，主观题进入待阅卷，并处理超时交卷。 |
| `exam/ExamScheduler.java` | 每分钟扫描超时进行中的答卷，调用评分服务自动提交。 |
| `exam/QuestionImportRow.java` | 题库 Excel 导入行模型和字段映射。 |
| `exam/ResultExportRow.java` | 已发布考试成绩 Excel 导出的列定义。 |

## 4. 后端资源、构建与数据库

| 文件 | 职责 |
| --- | --- |
| `backend/pom.xml` | Maven 构建、Java 17、Spring Boot、MyBatis-Plus、Flyway、JWT、EasyExcel、OSS 等依赖；构建时并入前端静态资源。 |
| `backend/src/main/resources/application.yml` | 数据库、上传大小、时区、JWT、初始管理员、演示账号、存储策略和 CORS 的环境变量默认值。 |
| `backend/src/main/resources/templates/question-bank-template.xlsx` | 旧版题库模板样例；当前下载接口根据 `QuestionImportRow` 动态生成包含专业标签列的新模板。 |

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

## 5. 前端结构

前端源码位于 `frontend/src/`。组件统一使用 Vue 3 Composition API（`<script setup>`），HTTP 访问经 `api.ts`，认证状态经 Pinia，路由元数据和菜单同时做前端可见性控制；最终访问权限仍由后端验证。

### 应用骨架、请求与工具

| 文件 | 职责 |
| --- | --- |
| `main.ts` | 创建 Vue 应用，注册 Pinia、Vue Router、Element Plus、全局样式并挂载。 |
| `App.vue` | 应用根组件，仅渲染路由出口。 |
| `router.ts` | 声明登录页、应用布局和所有业务路由；路由守卫处理登录、强制改密、员工台账跳转和权限拦截。 |
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
| `views/LoginView.vue` | 登录表单、前端校验、错误提示和登录后路由跳转。 |
| `views/DashboardView.vue` | 请求概览统计并用 ECharts 展示培养进度和成绩分布。 |
| `views/EmployeesView.vue` | 员工台账的筛选、分页、新建/编辑、导师绑定与基础资料维护。 |
| `views/EmployeeDirectoryView.vue` | 人员目录筛选、分页、Excel 导出及管理员调站审核入口。 |
| `views/ProfileView.vue` | 当前用户改密；员工可维护本人资料、提交调站申请并查看审批记录。 |
| `views/StationChangeReviewView.vue` | 管理员集中查询、通过或拒绝服务站变更申请。 |
| `views/CoursesView.vue` | 课程、场次、报名、签到码、自助签到、人工签到和签到记录的操作界面。 |
| `views/TrainingPlansView.vue` | 培养计划与计划任务的创建、编辑、启停、排序和删除界面。 |
| `views/TasksView.vue` | 任务下达、个人任务、提交/重提、附件下载与安全预览、审核、进度明细和筛选。 |
| `views/EvaluationView.vue` | 评分方案、评分项录入、分数覆盖、加扣分以及月度/季度汇总生成、发布、重开。 |
| `views/exams/ExamQuestionBankView.vue` | 题目标签、手工新增、启停和 Excel 模板/导入。 |
| `views/exams/ExamPapersView.vue` | 手动、随机和按员工专业动态“一人一卷”组卷。 |
| `views/exams/ExamPlansView.vue` | 考试时间、试卷、批次/板块范围和参考员工选择及发布。 |
| `views/exams/MyExamsView.vue` | 员工考试列表、作答自动保存、计时和防作弊事件上报。 |
| `views/exams/ExamResultsView.vue` | 考试完成情况、员工成绩明细、成绩发布和 Excel 导出。 |
| `views/exams/examUi.ts` | 考试状态、题型和日期显示的共享前端工具。 |
| `views/UsersView.vue` | 账号列表、创建、启停、重置密码、改角色/账号名/显示名及站点负责人范围配置。 |

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
| `frontend/vite.config.ts` | Vue 插件、`@`→`src` 别名、5173 端口与 `/api` 本地代理。 |

## 6. 测试文件

后端测试位于 `backend/src/test/java/com/talent/platform/`，主要覆盖纯规则和 Controller/服务关键分支。执行 `cd backend; mvn test`；前端工具函数执行 `cd frontend; npm run test`。

| 文件 | 覆盖内容 |
| --- | --- |
| `employee/EmployeeDirectoryControllerTest.java` | 人员目录筛选、数据范围或导出相关行为。 |
| `employee/EmployeeProfileControllerTest.java` | 员工个人资料读取、编辑边界与权限限制。 |
| `evaluation/EvaluationRulesTest.java` | 评价权重校验和最终得分计算。 |
| `exam/ExamScoringServiceTest.java` | 客观题/多选题答案比对和阅卷逻辑。 |
| `security/JwtServiceTest.java` | JWT 创建、解析和失效相关行为。 |
| `security/PermissionServiceTest.java` | 角色权限集和数据范围过滤规则。 |
| `security/SecurityUtilsTest.java` | 当前登录用户读取工具。 |
| `task/TaskControllerTest.java` | 任务接口的主要权限与业务分支。 |
| `task/TaskStatusServiceTest.java` | 逾期任务刷新与截止时间调度计算。 |
| `training/TrainingPlanControllerTest.java` | 培养计划和计划任务的管理接口。 |
| `user/UserControllerTest.java` | 账号管理、角色和站点范围的规则。 |

## 7. Windows 启动器与部署辅助文件

| 文件 | 职责 |
| --- | --- |
| `launcher/Program.cs` | Windows Forms 启动器主体：单实例互斥、Docker Desktop 检查/启动、MySQL 启停、Java JAR 生命周期、健康检查、浏览器打开和基于源码的 Docker 构建更新。 |
| `launcher/TalentPlatformLauncher.csproj` | .NET 8 Windows Forms 单文件、自包含、`win-x64` 发布配置。 |
| `launcher/app.manifest` | Windows 应用清单（系统兼容性与运行方式）。 |
| `docker-compose.yml` | MySQL 8.4 容器、默认开发账号、端口、健康检查和命名卷。 |

`launcher/bin/`、`launcher/obj/`、`backend/target/`、`frontend/dist/` 等为构建产物，不是需要维护的源代码；请勿将它们作为业务修改入口。

## 8. 接手时的关键约束

- 权限校验必须同时考虑“功能权限”和“员工数据范围”；不要仅依赖前端菜单隐藏。
- JWT 通过 `security_version` 支持账号启停、角色调整、改密后的旧令牌失效；涉及账号安全的修改应保持这一机制。
- 已合并的 Flyway 迁移不能修改；数据库变更新增 `Vx__description.sql`。
- 已发布的月度评价会锁定；修改评价计算或评分项时先检查 `EvaluationService` 与相关迁移。
- 文件下载必须继续经任务所属员工范围校验；本地文件实现已专门防范路径穿越。
- 发布时，前端必须先构建，后端 Maven 才会将 `frontend/dist` 一并打入 JAR。
