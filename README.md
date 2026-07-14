# 新员工“一人一画像”培养管理平台

面向新员工、导师、站点负责人、培训管理员、管理员和超级管理员的培养管理平台。当前版本是可本地运行和继续迭代的 MVP，核心能力包括人员台账、课程签到、闯关任务、综合评价、考试题库、权限隔离、审计日志和个人培养进度展示。

仓库地址：<https://github.com/Heart1ess1/talent-development-platform>

## 项目定位

本项目用于支撑新员工培养过程中的人员管理、学习过程记录、任务闯关、考试评价和权限分层管理。开发阶段重点是先保证本地可运行、业务闭环可演示、后续协作者能按统一规则继续开发。

适合新协作者先阅读：

- [CONTRIBUTING.md](CONTRIBUTING.md)：GitHub 协作、分支、提交、推送、Pull Request 和版本发布规则。
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
- 登录与安全：JWT 登录态、首次登录强制改密、账号停用/角色调整/密码重置后旧令牌失效。
- 人员台账：员工资料维护、Excel 导入、人员目录查询。
- 课程与任务：课程签到、闯关任务、培养进度展示。
- 综合评价：批次评分方案、三方月评、加扣分、月度和季度锁定快照。
- 考试管理：题库、试卷、考试计划、自动保存、客观题自动阅卷、主观阅卷、补考次数和防作弊事件。
- 审计和权限：按角色和数据范围控制可访问内容，关键操作写入审计记录。

## 环境要求

开发模式需要本机安装：

- JDK 17（项目编译目标；更高版本 JDK 通常也可运行 Maven）
- Maven 3.9+
- Node.js 24 LTS
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
npm install
npm run dev
```

访问：

```text
http://localhost:5173
```

开发环境初始超级管理员：

```text
用户名：superadmin
密码：ChangeMe123!
```

首次登录必须修改密码。部署或真实使用前必须通过环境变量覆盖默认密码和 `JWT_SECRET`。

## Windows 图形启动器

发布包目录为 `release/TalentPlatform`，该目录是本地构建产物，不提交到源码仓库。使用发布版时，应从 GitHub Releases 下载发布包并解压。

解压后：

1. 安装并启动 Docker Desktop。
2. 双击 `人才培养平台启动器.exe`。
3. 点击“启动平台”。

启动器提供两种启动方式：

- `启动平台`：直接运行当前发布版本。
- `更新并启动`：检测到同一项目中的 `frontend`、`backend` 源码时，通过 Docker 自动构建前后端、替换发布 JAR，然后启动最新版本。

关闭启动器会停止应用进程；数据库默认继续运行，可通过“停止数据库”按钮关闭。

开发者重新生成发布包时，依次构建前端、打包后端，再发布 `launcher/TalentPlatformLauncher.csproj`，并按当前发布目录结构放置 JAR、启动器和 Java 运行时。发布包不要提交到 Git，应压缩后上传到 GitHub Releases。

## 常用环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_URL` | 本机 `talent_platform` | MySQL JDBC 地址 |
| `DB_USERNAME` / `DB_PASSWORD` | `talent / talent_dev` | 数据库账号 |
| `JWT_SECRET` | 仅开发默认值 | 生产环境必须使用随机长密钥 |
| `JWT_EXPIRATION_MINUTES` | `120` | JWT 有效期 |
| `SUPER_ADMIN_USERNAME` / `SUPER_ADMIN_PASSWORD` | 开发账号 | 首次启动创建超级管理员 |
| `STORAGE_TYPE` | `local` | 设置为 `oss` 切换阿里云 OSS |
| `LOCAL_STORAGE_ROOT` | `../data/uploads` | 本地私有文件目录 |
| `OSS_ENDPOINT` / `OSS_BUCKET` | 空 | OSS 基础配置 |
| `OSS_ACCESS_KEY` / `OSS_SECRET_KEY` | 空 | OSS 凭证，禁止提交到 Git |

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

## 安全注意事项

- 不要提交 `.env`、数据库备份、真实用户数据、OSS 密钥、JWT 密钥或任何生产环境密码。
- `data/`、`release/` 和构建产物目录已被 `.gitignore` 忽略，除非明确有理由，否则不要改为提交。
- README 中的 `superadmin / ChangeMe123!` 只用于本地开发，真实部署必须覆盖。
- 任何涉及权限放宽、认证流程、文件上传、考试防作弊、评价锁定和审计日志的改动，都应在 Pull Request 中明确说明影响范围。
