# 未完成任务与上线检查清单

本文档只记录当前尚未完成、需要继续执行或持续检查的事项。完整产品任务历史仍保留在 [task-board.md](task-board.md)，阿里云操作细节以 [aliyun-deployment.md](aliyun-deployment.md) 为准。

## 当前基线

- 最后核对日期：2026-08-12
- GitHub：PR #3 已合并到 `main`，合并提交为 `0dc92decfc4d782b6a7b235cd59bc383dfba8ba8`。
- 云服务器：PR #3 尚未部署；线上仍运行本次合并之前的版本。
- 本次尚未发生：没有上传 PR #3 的新 JAR/前端资源，没有触发 Flyway V22～V24，没有重建应用容器，也没有执行 PR #3 上线后的业务冒烟测试。
- 基础设施现状：100 GiB 数据盘、MySQL 数据目录、两个私有 ACL OSS Bucket、ECS RAM Role、HTTPS 和本地备份已经投入使用；ICP备案、正式 DNS 和 CDN 尚未完成。

“代码已合并”不等于“云端已上线”。只有部署任务取得服务器端版本、健康检查、迁移记录和业务冒烟证据后，才能将状态改为 `Done`。

## 状态说明

| 状态 | 含义 |
| --- | --- |
| `Ready` | 当前条件已经具备，可以直接执行 |
| `In Progress` | 已经开始执行，但尚未完成验收 |
| `Blocked` | 依赖备案、审核、DNS 等外部条件，当前不能完成 |
| `Recurring` | 需要按周期重复检查或执行 |
| `Backlog` | 不阻塞当前网站运行，可排期优化 |
| `Done` | 已完成且已经记录可复核证据 |

## P0：PR #3 云服务器部署

这些事项当前具备执行条件，也是让 PR #3 功能真正进入线上环境所必需的工作。必须按顺序执行。

| 完成 | ID | 状态 | 任务 | 原因/依赖 | 完成证据 |
| --- | --- | --- | --- | --- | --- |
| [ ] | DEPLOY-001 | Ready | 核对私有 Bucket 的实际 CORS，并加入 `POST` | PR #3 已将浏览器直传升级为 OSS POST Policy；只保留 `PUT` 会导致线上上传失败 | `yryhx-talent-private-cn-shanghai` 允许站点来源执行 `POST/GET/HEAD`，公共访问仍被阻止 |
| [ ] | DEPLOY-002 | Ready | 执行部署前 MySQL 备份并保存 SHA-256 | 新版本会执行 Flyway V22～V24，部署前必须保留可验证回退点 | `/data/talent-platform/backups/mysql/` 生成新的 `.sql.gz` 和对应 `.sha256`，gzip 校验通过 |
| [ ] | DEPLOY-003 | Ready | 从 `origin/main@0dc92de` 构建 IP 版生产 JAR | ICP/CDN 尚未就绪，本轮不能部署引用 `static.yryhx.cn` 的 CDN 构建 | 前端 `AssetBase` 为 `/`；前端测试、构建和后端测试通过；记录新 JAR SHA-256 |
| [ ] | DEPLOY-004 | Ready | 上传新 JAR、部署脚本及配置模板到 ECS | GitHub 合并不会自动把本地文件发送到服务器 | ECS 上待部署文件哈希与本机构建产物一致，脚本版本包含 OSS POST 上传支持 |
| [ ] | DEPLOY-005 | Ready | 使用 `update-app.sh` 更新应用并触发 Flyway | 让考试自动发布、课件学习和安全直传代码真正运行 | 应用容器重建成功，`/actuator/health` 为 `UP`，Flyway 当前版本为 24 |
| [ ] | DEPLOY-006 | Ready | 执行 OSS、课件、附件及核心业务冒烟测试 | 单纯健康检查不能证明上传、预览、权限和自动成绩流程可用 | `verify-oss-switch.sh`、`smoke-oss-app.sh`、`smoke-private-courseware.sh`、`smoke-private-attachment.sh` 全部通过；考试结束后成绩可见性回归通过 |
| [ ] | DEPLOY-007 | Ready | 记录线上版本并验证回滚材料 | 后续必须能够判断服务器到底运行哪个提交 | 记录 Git 提交、JAR SHA-256、部署时间、数据库迁移版本、备份文件和回滚 JAR 路径 |

部署时使用根路径静态资源构建：

```powershell
powershell -ExecutionPolicy Bypass -File deploy/aliyun/build-production.ps1 -AssetBase "/"
```

完成部署后，至少保存以下服务器端输出：

```text
Git commit
JAR SHA-256
docker compose ps
/actuator/health
flyway_schema_history 当前版本
四个 OSS/业务冒烟脚本结果
部署前备份文件及 SHA-256
```

任一 P0 验收失败时停止后续步骤，保留错误日志，并使用部署前 JAR、`.env` 和数据库备份评估回滚；不要在失败状态下继续 DNS/CDN 切换。

## 外部条件阻塞任务

| 完成 | ID | 状态 | 任务 | 阻塞原因 | 解除阻塞后的验收标准 |
| --- | --- | --- | --- | --- | --- |
| [ ] | ICP-001 | Blocked | 等待备案系统自动提交管局 | 当前为“待提交管局”，域名注册/转入时间条件尚未满足 | 状态变为已提交管局并记录提交时间 |
| [ ] | ICP-002 | Blocked | 完成管局审核和短信核验 | 必须等待管局流程发起 | 获得备案号，短信核验完成且备案状态正常 |
| [ ] | CDN-001 | Blocked | 创建并配置 `static.yryhx.cn` CDN 域名 | 中国内地 CDN 域名需要备案号 | OSS 私有回源鉴权、HTTPS 证书、TLS 1.2/1.3、缓存规则配置完成 |
| [ ] | DNS-001 | Blocked | 添加根域名、`www` 和 `static` 正式 DNS 记录 | 依赖备案号和 CDN 分配的真实 CNAME | `@` 指向 ECS，`www` 正确跳转，`static` 指向 CDN CNAME，TTL 初始为 600 秒 |
| [ ] | CDN-002 | Blocked | 激活 CDN 专用候选构建 | 必须先确认 CDN 域名、证书、CNAME 和静态资源全部可用 | `activate-cdn-release.sh` 通过，静态资源 200、MIME 正确并命中缓存 |
| [ ] | GO-LIVE-001 | Blocked | 完成正式域名全链路验收并观察 24 小时 | 依赖 DNS/CDN 正式切换 | HTTPS、API、登录、考试、课件、附件、OSS 权限和 CDN 缓存持续正常，之后再提高 TTL |

## 运行安全与可靠性

| 完成 | ID | 优先级 | 状态 | 任务 | 完成标准 |
| --- | --- | --- | --- | --- | --- |
| [ ] | SEC-001 | P1 | Ready | 重新执行后端完整依赖漏洞扫描 | OWASP Dependency-Check/NVD 或等效扫描成功完成，结果归档；高危漏洞为 0 或已有明确处置 |
| [ ] | CI-001 | P1 | Ready | 为 PR 配置 GitHub Actions 门禁 | 后端测试、前端测试/构建、依赖审计和脚本语法检查在 PR 中自动执行 |
| [ ] | BACKUP-001 | P1 | Ready | 执行一次隔离环境 MySQL 恢复演练 | 从最新 `.sql.gz` 在隔离数据库恢复成功，关键表数量和登录/核心查询通过 |
| [ ] | BACKUP-002 | P1 | Ready | 建立与数据盘故障域隔离的备份 | 至少启用云盘快照或加密异地备份之一，并验证保留策略和恢复权限 |
| [ ] | CERT-001 | P1 | Recurring | 续签或替换测试证书 | 在 2026-10-26 前完成，避免证书于 2026-11-10 到期；续签后重新执行 TLS 验收 |

## 非阻塞开发优化

| 完成 | ID | 优先级 | 状态 | 任务 | 完成标准 |
| --- | --- | --- | --- | --- | --- |
| [ ] | QA-001 | P2 | Backlog | API 契约自动校验 | 自动发现 Controller 端点与 `api-contract.md` 的遗漏或漂移 |
| [ ] | QA-002 | P2 | Backlog | 核心流程 E2E 自动化 | 覆盖登录、人员、课程、任务、考试、评价和课件安全预览 |
| [ ] | PERF-001 | P2 | Backlog | 前端大分块优化 | 降低首次加载体积，构建不再出现当前大分块警告，并记录加载指标 |
| [ ] | UX-001 | P3 | Backlog | 页面文案与错误提示一致性整理 | 同类操作、按钮、状态和错误提示使用一致、可理解的中文文案 |

## 维护规则

1. 每次开始任务前先核对依赖条件和当前线上证据，不能仅凭旧文档判断状态。
2. 状态变化时同步更新本文件；任务完成后勾选复选框、改为 `Done`，并补充日期和证据路径或命令结果。
3. 涉及代码、接口或权限的任务同时更新 [task-board.md](task-board.md)、[api-contract.md](api-contract.md) 或 [permissions-matrix.md](permissions-matrix.md)。
4. 涉及 ECS、OSS、CDN、DNS、证书、数据盘或备份的任务同时更新 [aliyun-deployment.md](aliyun-deployment.md)。
5. 不在 Markdown 中记录密码、私钥、AccessKey、完整签名 URL 或验证码。
