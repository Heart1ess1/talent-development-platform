# 新员工“一人一画像”培养管理平台

面向新员工、导师、管理员和超级管理员的培养管理 MVP，包含人员台账、课程签到、闯关任务、审核评分、权限隔离和个人进度展示。

## 环境要求

- JDK 17（项目编译目标；更高版本 JDK 也可运行 Maven）
- Maven 3.9+
- Node.js 24 LTS
- Docker Desktop / Docker Compose（仅用于本地 MySQL）

## 本地启动

### Windows 图形启动器（推荐）

发布目录为 `release/TalentPlatform`。安装并启动 Docker Desktop 后，双击 `人才培养平台启动器.exe`，点击“启动平台”即可；启动器不调用 PowerShell 或 CMD，也不需要系统预装 Java、Node.js、Maven 或 .NET。

启动器提供两种启动方式：`启动平台`直接运行当前发布版本；`更新并启动`会在检测到同一项目中的 `frontend`、`backend` 源码时，通过 Docker 自动构建前后端、替换发布 JAR，然后启动最新版本。构建日志会实时显示在启动器中。关闭启动器会停止应用进程；数据库默认继续运行，可通过“停止数据库”按钮关闭。

开发者重新生成发布包时，依次构建前端、打包后端，再发布 `launcher/TalentPlatformLauncher.csproj`，并按当前发布目录结构放置 JAR、启动器和 Java 运行时。

### 开发模式

```powershell
docker compose up -d
cd backend
mvn spring-boot:run
```

新终端中启动前端：

```powershell
cd frontend
npm install
npm run dev
```

访问 `http://localhost:5173`。开发环境初始超级管理员为 `superadmin / ChangeMe123!`，首次登录必须修改密码。部署前必须通过环境变量覆盖该密码与 `JWT_SECRET`。

## 常用环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_URL` | 本机 `talent_platform` | MySQL JDBC 地址 |
| `DB_USERNAME` / `DB_PASSWORD` | `talent / talent_dev` | 数据库账号 |
| `JWT_SECRET` | 仅开发默认值 | 生产环境必须使用随机长密钥 |
| `SUPER_ADMIN_USERNAME` / `SUPER_ADMIN_PASSWORD` | 开发账号 | 首次启动创建超级管理员 |
| `STORAGE_TYPE` | `local` | 设置为 `oss` 切换阿里云 OSS |
| `LOCAL_STORAGE_ROOT` | `../data/uploads` | 本地私有文件目录 |
| `OSS_ENDPOINT` / `OSS_BUCKET` | 空 | OSS 基础配置 |
| `OSS_ACCESS_KEY` / `OSS_SECRET_KEY` | 空 | OSS 凭证，禁止提交到 Git |

数据库结构由 Flyway 自动创建。人员 Excel 导入采用整批校验：任一行错误时整批不写入。

## 验证

```powershell
cd backend; mvn test
cd ../frontend; npm run test; npm run build
```

## 权限与培养评价

- 固定角色：`EMPLOYEE`、`MENTOR`、`STATION_MANAGER`、`TRAINING_ADMIN`、`ADMIN`、`SUPER_ADMIN`。
- 登录上下文由 `/api/v1/auth/me` 返回权限点和数据范围；账号停用、角色调整或密码重置会立即使旧令牌失效。
- 综合评价接口位于 `/api/v1/evaluation`，支持批次评分方案、三方月评、加扣分、月度及季度锁定快照。
- 考试接口位于 `/api/v1/exams`，支持题库、试卷、考试计划、自动保存、客观题自动阅卷、主观阅卷、补考次数和防作弊事件。
- 月度汇总在次月 1 日 02:00 生成，季度汇总在 1/4/7/10 月 1 日 03:00 生成，时区均为 `Asia/Shanghai`。

数据库升级由 Flyway 的 `V2__permissions_evaluation_exam.sql` 自动执行。升级生产环境前必须备份数据库并在同版本 MySQL 上演练迁移。

运行时文件仅写入被 Git 忽略的 `data/`，仓库不生成额外报告或交付文档目录。
