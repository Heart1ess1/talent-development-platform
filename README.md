# 新员工“一人一画像”培养管理平台

面向新员工、导师、站点负责人、培训管理员、管理员和超级管理员的培养管理平台。当前版本是可本地运行和继续迭代的 MVP，核心能力包括人员信息管理、位置报备与人员流动、课程签到、培养计划、任务下发与任务跟踪、综合评价、考试题库、权限隔离、审计日志和个人培养进度展示。

仓库地址：<https://github.com/Heart1ess1/talent-development-platform>

## 项目定位

本项目用于支撑新员工培养过程中的人员管理、学习过程记录、任务闯关、考试评价和权限分层管理。开发阶段重点是先保证本地可运行、业务闭环可演示、后续协作者能按统一规则继续开发。

适合新协作者先阅读：

- [CONTRIBUTING.md](CONTRIBUTING.md)：GitHub 协作、分支、提交、推送、Pull Request 和版本发布规则。
- [docs/README.md](docs/README.md)：需求口径、API 合同、权限矩阵和任务表等项目文档入口。
- [docs/codebase-guide.md](docs/codebase-guide.md)：面向新加入或接手同事的代码结构、业务链路和逐文件职责导览。
- [docs/aliyun-deployment.md](docs/aliyun-deployment.md)：ECS、私有 OSS 签名传输、公共 OSS＋CDN、`yryhx.cn`、迁移和上线验收基线。
- 本 README：项目能力、目录结构、运行方式、验证命令和发布包说明。

## 技术栈

- 后端：Java 17、Spring Boot、Spring Security、MyBatis-Plus、Flyway、MySQL
- 前端：Vue 3、TypeScript、Vite、Pinia、Element Plus
- 数据库：MySQL，本地通过 Docker Compose 启动
- 桌面启动器：C# / .NET Windows Forms，用于发布包的一键启动

## 目录结构

```text
.
├── backend/                  # Spring Boot 后端服务
│   ├── src/main/java/        # 后端业务代码
│   ├── src/main/resources/   # 配置、Flyway 数据库迁移、模板文件
│   └── src/test/java/        # 后端测试
├── frontend/                 # Vue 前端项目
│   ├── src/                  # 页面、路由、状态、接口封装
│   └── package.json          # 前端脚本与依赖
├── launcher/                 # Windows 图形启动器源码
├── docs/                     # 需求、API、权限和任务文档
├── deploy/aliyun/            # 阿里云生产部署配置、安装、更新和验收脚本
├── docker-compose.yml        # 本地 MySQL
├── .gitignore                # Git 忽略规则
└── CONTRIBUTING.md           # 协作开发指南
```

以下目录是本地运行或构建产物，不提交到 GitHub：

- `data/`：本地上传文件和运行时数据
- `release/`：本地发布包目录
- `outputs/`：临时输出文件
- `.pnpm-store/`、`frontend/node_modules/`、`backend/target/`、`frontend/dist/`、`launcher/bin/`、`launcher/obj/`

## 核心功能

- 用户和角色：支持 `EMPLOYEE`、`MENTOR`、`STATION_MANAGER`、`TRAINING_ADMIN`、`ADMIN`、`SUPER_ADMIN`。
- 角色化进度概览：员工首页集中呈现个人任务、课程/考试日程、完成进度、季度评分和导师反馈；管理首页聚合职责待办、任务/课程/考试/评价进度、近期安排和风险员工。
- 登录与安全：JWT 登录态、首次登录强制改密、账号停用/角色调整/密码重置后旧令牌失效。
- 人员管理：作为一级业务模块，下设“人员台账”“人员流动”和“调站审批”；台账统一完成人员查询、新增编辑、板块与双导师维护和 Excel 导入导出，调站审批按原有权限独立控制。
- 人员流动：员工自主报备临时位置、变动时间和原因；导师、站点负责人及管理角色按人员范围查看当前位置、统计与连续轨迹。
- 课程与任务：Word、PDF、PPT、OFD、图片课件统一转换为带姓名/工号水印的只读页面，配合课件学习跟踪、场次签到、培养计划、带附件任务下发、任务跟踪和培养进度展示。
- 综合评价：工作台、按任务和人员范围配置评分人、我的评分任务、员工待评队列、可复用评价模板和结果中心；支持按全员、批次或板块为同类任务统一分配多名评分人，全部提交后自动取平均分，以及评分项/任务/考试两级权重、自动取分、跨站点在站天数加权、加扣分和月度/季度锁定快照。
- 考试管理：多题库分类、题目专业标签、客观题手动/随机/一人一卷组卷、考试计划、自动保存与评分、整场考试结束后自动下发成绩、成绩导出、补考次数和防作弊事件。
- 审计和权限：按角色和数据范围控制可访问内容，关键操作写入审计记录。

### 近期结构调整与维护原因

- “人员管理”作为一级导航分组，依次下设“人员台账”“人员流动”和“调站审批”。人员台账承接新增、编辑、筛选、导入导出、双导师设置和完整档案；旧 `/employees` 浏览器地址仅用于兼容跳转。
- `/api/v1/employees` 后端接口仍然保留。课程、任务、评价等模块需要复用人员选择数据，因此“移除重复页面”不等于删除共享业务 API。
- 考试中心按“题库管理—试卷管理—考试计划—成绩管理”的业务顺序组织；交卷后管理员可立即查看客观题得分，员工必须等整场考试结束后才能看到系统自动下发的成绩。
- 综合评价按“模板定义—月份应用—评分任务生成—按人员范围配置评分人—个人评分—汇总发布”组织；管理员先选择导师、站点或培训任务，再按全员、批次、板块设置评分人，系统自动展开到员工任务；板块规则优先于批次规则，批次规则优先于全员默认，全部当前评分人提交后才形成正式平均分。
- 进度概览不再复用同一套粗粒度统计：`EMPLOYEE` 返回个人成长主页，其他角色按 `SELF`、`MENTORED`、`STATION`、`ALL` 数据范围返回培养运营工作台，并只显示当前角色有权处理的待办入口。
- 服务站变更统一写入 `station_change_request`。员工申请经审批生效，管理员直接编辑人员站点时也写入已生效历史，避免人员当前站点与历史轨迹不一致。
- `dev-wanben` 的人员资料与调站功能、`dzw_exam_TuoZhan` 的动态考试功能已经过迁移编号调整和现有架构适配。后续不要再次直接合并这两个原始分支，应以当前整合分支或其合并后的 `main` 为新开发基线。

## 项目文档

GitHub 仓库首页只会自动显示根目录的 `README.md`。`docs/` 下的 Markdown 文件不会自动展开到首页，需要通过链接进入。

当前文档入口：

- [docs/requirements.md](docs/requirements.md)：阶段 1 MVP 需求口径、目标用户、核心业务闭环和已支持范围。
- [docs/api-contract.md](docs/api-contract.md)：当前 `/api/v1` 接口约定、认证、响应结构、错误码和主要接口清单。
- [docs/permissions-matrix.md](docs/permissions-matrix.md)：角色、权限点、数据范围和前端路由权限。
- [docs/task-board.md](docs/task-board.md)：轻量任务表，用于在未配置 GitHub Project 前追踪协作任务。
- [docs/pending-tasks.md](docs/pending-tasks.md)：当前未完成任务、历次云端部署记录、外部阻塞条件和验收证据清单。
- [docs/codebase-guide.md](docs/codebase-guide.md)：代码库结构、模块关系和每个源码/配置/迁移/测试文件的职责。
- [docs/business-flow-acceptance-2026-08-13.md](docs/business-flow-acceptance-2026-08-13.md)：全系统业务流程验收结果、主要问题、发布判定和优化实施顺序。
- [docs/completed-work-summary-since-2026-07-29.md](docs/completed-work-summary-since-2026-07-29.md)：汇总指定范围内已经实现、合并、部署或验收完成的项目成果。
- [docs/aliyun-deployment.md](docs/aliyun-deployment.md)：当前云端证据、OSS/CDN 安全分层、域名备案、迁移、回退和上线验收。

也可以从 [docs/README.md](docs/README.md) 进入文档目录。

## 环境要求

开发模式需要本机安装：

- JDK 17（项目编译目标；更高版本 JDK 通常也可运行 Maven）
- Maven 3.9+
- Node.js 24 LTS
- Corepack / pnpm（安装 Node.js 后执行 `corepack enable`）
- Docker Desktop / Docker Compose（用于本地 MySQL）

发布包运行模式只要求安装并启动 Docker Desktop。发布包会携带运行所需的 Java 运行时、后端 JAR、前端静态文件和 Windows 启动器，不要求系统预装 Java、Node.js、Maven 或 .NET。

## 本地开发启动

先启动数据库：

```powershell
docker compose up -d
```

启动后端：

```powershell
cd backend
mvn spring-boot:run
```

新开一个终端启动前端：

```powershell
cd frontend
corepack pnpm install --frozen-lockfile
corepack pnpm run dev
```

访问：

```text
http://localhost:5173
```

当前测试版内置以下测试账号，首次登录无需修改密码：

| 权限 | 用户名 | 密码 |
| --- | --- | --- |
| 新员工 | `employee` | `12345678` |
| 管理员 | `admin` | `12345678` |
| 培训管理员 | `trainadmin` | `12345678` |
| 导师 | `mentor` | `12345678` |
| 超级管理员 | `superadmin` | `superadmin` |

这些账号仅用于测试和演示。部署或真实使用前必须关闭测试账号引导，设置正式管理员账号，并通过环境变量覆盖默认密码和 `JWT_SECRET`。

新协作者本地启动时建议按以下顺序检查：

1. 确认 Docker Desktop 已启动，再执行 `docker compose up -d` 启动 MySQL。
2. 在 `backend/` 目录执行 `mvn spring-boot:run`，等待 Flyway 迁移完成并看到 Spring Boot 启动成功。
3. 新开终端进入 `frontend/`，首次运行执行 `corepack pnpm install --frozen-lockfile`，之后执行 `corepack pnpm run dev`。
4. 打开 `http://localhost:5173`，使用上方测试账号登录。
5. 本地演示或联调前确认未使用生产密码、真实用户数据、OSS 密钥或生产 `JWT_SECRET`。

## Windows 图形启动器

发布包目录为 `release/TalentPlatform`，该目录是本地构建产物，不提交到源码仓库。使用发布版时，应从 GitHub Releases 下载发布包并解压。

解压后：

1. 安装并启动 Docker Desktop。
2. 双击 `人才培养平台启动器.exe`。
3. 点击“启动发布版”。

启动器将客户运行、源码开发和发布构建分为三个独立流程：

- `启动发布版`：运行发布包中的 JAR，访问 `http://localhost:8080`。纯发布包环境只显示这条有效运行路径。
- `启动开发模式`：仅在检测到 `frontend/` 和 `backend/` 源码时可用。启动器直接运行 `mvn spring-boot:run` 和 Vite 开发服务器，访问 `http://localhost:5173`，不生成 JAR；前端源码支持热更新。
- `重启开发模式`：开发模式运行后，同一按钮用于快速重启 Maven 与 Vite。Java 源码修改后使用该按钮即可重新编译并运行，不需要执行发布打包。
- `构建发布版`：依次同步 pnpm 依赖、构建前端并执行 `mvn clean package -DskipTests`。该操作只生成或更新发布 JAR，不会自动启动应用。

开发模式要求本机安装 JDK、Maven、Node.js 与 Corepack/pnpm。可通过环境变量 `TALENT_PLATFORM_SOURCE_ROOT` 显式指定源码根目录，通过 `TALENT_PLATFORM_HOME` 指定发布包根目录。

关闭启动器会终止由启动器创建的后端和前端进程；数据库默认继续运行，可通过“停止数据库”按钮关闭。若启动器发现端口 `8080` 上已有健康实例，只会打开该实例，不会接管或终止外部进程。

数据库启动前会等待 MySQL 健康检查完成。如果发布包中存在 `images/mysql-8.4.tar` 且本机没有 `mysql:8.4` 镜像，启动器会优先执行离线导入；否则 Docker 会通过已配置的镜像源下载。

开发者重新生成发布包时，依次构建前端、打包后端，再发布 `launcher/TalentPlatformLauncher.csproj`，并按当前发布目录结构放置 JAR、启动器和 Java 运行时。发布包不要提交到 Git，应压缩后上传到 GitHub Releases。

## 常用环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_URL` | 本机 `talent_platform` | MySQL JDBC 地址 |
| `DB_USERNAME` / `DB_PASSWORD` | `talent / talent_dev` | 数据库账号 |
| `JWT_SECRET` | 仅开发默认值 | 生产环境必须使用随机长密钥 |
| `JWT_EXPIRATION_MINUTES` | `120` | JWT 有效期 |
| `SUPER_ADMIN_USERNAME` / `SUPER_ADMIN_PASSWORD` | 开发账号 | 首次启动创建超级管理员 |
| `DEMO_USERS_ENABLED` | `true` | 是否启用测试账号引导；真实部署应设为 `false` |
| `STORAGE_TYPE` | `local` | 设置为 `oss` 切换阿里云 OSS |
| `LOCAL_STORAGE_ROOT` | `../data/uploads` | 本地私有文件目录；启动器会让源码模式与发布模式共用同一绝对目录，避免切换启动方式后附件不可访问 |
| `OSS_ENDPOINT` | 空 | ECS 访问上海 OSS 的内网 Endpoint |
| `OSS_PUBLIC_ENDPOINT` | 空 | 为浏览器签发直传/下载 URL 的公网 Endpoint，不能填写 `-internal` 地址 |
| `OSS_PRIVATE_BUCKET` | 空 | 课件原件、任务附件和成果文件的私有 Bucket |
| `OSS_PUBLIC_BUCKET` | 空 | 前端静态资源与头像的公共资源 Bucket；Bucket ACL 仍应为私有 |
| `CDN_BASE_URL` | 空 | 公共资源 CDN 地址，例如 `https://static.yryhx.cn` |
| `OSS_BUCKET` | 空 | 旧版单 Bucket 兼容配置；生产环境应改用私有/公共两个 Bucket |
| `OSS_ACCESS_KEY` / `OSS_SECRET_KEY` | 空 | OSS 凭证，禁止提交到 Git |
| `OSS_RAM_ROLE` | 空 | ECS 上推荐填写实例 RAM 角色名，以临时凭证访问 OSS；设置后无需长期 AccessKey |

数据库结构由 Flyway 自动创建和升级。人员 Excel 导入采用整批校验：任一行错误时整批不写入。

## 验证命令

后端测试：

```powershell
cd backend
mvn test
```

前端测试和构建：

```powershell
cd frontend
npm run test
npm run build
```

提交 Pull Request 前至少应运行与本次修改相关的测试。涉及后端接口、权限、数据库迁移或评价/考试规则时，应优先运行后端测试；涉及页面、路由、状态管理或前端工具函数时，应优先运行前端测试和构建。

## 阶段验收状态

阶段 1-5 验证与收口已在本地完成以下检查：

| 检查项 | 命令 | 结果 |
| --- | --- | --- |
| 后端测试 | `cd backend && mvn test` | 通过，44 个测试全部成功。 |
| 前端测试 | `cd frontend && npm run test` | 通过，3 个测试全部成功。 |
| 前端生产构建 | `cd frontend && npm run build` | 通过，产物生成到 `frontend/dist/`。 |
| 前端依赖审计 | `cd frontend && npm audit` | 通过，0 个已知漏洞。 |
| Windows 启动器 | `dotnet build launcher/TalentPlatformLauncher.csproj -c Release` | 通过，0 个警告、0 个错误。 |
| 数据库迁移 | 启动后检查 `flyway_schema_history` | V1-V15 全部执行成功。 |

本次收口已同步 README、API 合同、代码库导览、权限矩阵和任务表；P0 目标任务均为 `Done`。P2/P3 后续项保留在 `docs/task-board.md` 中，作为下一阶段明确剩余工作。

## 数据库和迁移

数据库升级由 Flyway 自动执行，迁移脚本位于：

```text
backend/src/main/resources/db/migration/
```

规则：

- 已经合并到 `main` 的迁移脚本不要修改历史内容，应新增 `Vx__description.sql`。
- 生产环境升级前必须备份数据库，并在同版本 MySQL 上演练迁移。
- 涉及字段删除、数据清洗或不可逆操作时，必须在 Pull Request 中说明风险和回滚方案。

## GitHub 协作入口

本项目使用 GitHub 进行版本维护和协同开发。新协作者不要直接向 `main` 分支提交代码，推荐流程是：

1. 从 `main` 创建功能分支。
2. 在功能分支完成修改。
3. 本地运行测试。
4. 推送分支到 GitHub。
5. 创建 Pull Request。
6. 代码检查通过后再合并回 `main`。

整合多个协作者分支时，不要仅依据“文件能否自动合并”判断完成：

- 先确认各分支的共同基线、迁移版本和修改模块。
- 对与本地架构冲突的提交使用择取并适配，提交说明中记录来源分支和原提交。
- 整合后比较最终 Git tree，运行完整测试，再推送新的集成分支。
- 已经适配进入集成分支的原始分支不要重复 merge。

完整操作说明见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 版本发布

源码仓库只保存源代码、配置、迁移脚本和必要模板。发布包通过 GitHub Releases 分发，不放入普通 Git 提交。

建议版本号格式：

```text
v0.1.0
v0.2.0
v1.0.0
```

发布时应包含：

- 发布包 zip，例如 `TalentPlatform-v0.1.0.zip`
- 本次变更说明
- 已知问题
- 升级注意事项，尤其是数据库迁移和环境变量变化

## 已知问题

- `npm run build` 当前会输出来自第三方依赖 `@vueuse/core` 的 PURE 注释位置警告，不影响构建结果。
- 前端构建会提示部分 chunk 超过 500 kB。当前 MVP 暂不引入代码分割优化，后续可在页面稳定后按路由和图表依赖拆包。
- 自动化 API 契约校验、端到端核心流程脚本、生产部署/备份恢复说明和页面文案一致性梳理仍是后续任务，详见 `docs/task-board.md` 的 QA、OPS 和 UX 条目。
- 当前生产化运维方案不属于 MVP 验收范围；真实部署前仍需补充备份、恢复、升级演练和密钥管理流程。

## 安全注意事项

- 不要提交 `.env`、数据库备份、真实用户数据、OSS 密钥、JWT 密钥或任何生产环境密码。
- `data/`、`release/` 和构建产物目录已被 `.gitignore` 忽略，除非明确有理由，否则不要改为提交。
- README 中的测试账号只用于测试和演示，真实部署必须关闭测试账号引导并覆盖默认密钥。
- 任何涉及权限放宽、认证流程、文件上传、考试防作弊、评价锁定和审计日志的改动，都应在 Pull Request 中明确说明影响范围。
