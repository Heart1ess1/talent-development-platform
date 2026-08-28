# 未完成任务与上线检查清单

本文档只记录当前尚未完成、需要继续执行或持续检查的事项。完整产品任务历史仍保留在 [task-board.md](task-board.md)，阿里云操作细节以 [aliyun-deployment.md](aliyun-deployment.md) 为准。

## 当前基线

- 最后核对日期：2026-08-28
- GitHub：成果提交与上传进度 PR #31、CDN 缓存就绪检查兼容性修复 PR #32 均已合并；远端 `main` 为 `191c600ea72a387170ab4257b618c0c7b09d6894`，本次生产功能提交为 `bc09ffc12be4338cbbacc7ce94354192e82d711a`。
- 云服务器：已激活成果提交优化版 CDN 生产 JAR，SHA-256 为 `e1cc805303b42691eae8ead74ab6be8234af71c059be99c268f01b6b6f2e1fb2`；部署前数据库备份为 `/data/talent-platform/backups/mysql/talent-platform-20260828-172010.sql.gz`，激活前回滚材料位于 `/data/talent-platform/releases/history/cdn-activation-20260828172402-2629519/`。
- 线上验收：应用健康状态为 `UP`，Flyway 已校验 35 个迁移且无需新增迁移；MySQL、应用和 Nginx 容器均正常，生产就绪检查为 `ready: true`，公网首页和新 CDN 资源正常，未登录删除票据接口返回 401，部署后日志无 `ERROR` 或 `Exception`；私有 OSS 签名上传、下载、删除及临时数据清理冒烟通过。员工成果提交弹窗的生产浏览器视觉复核仍待使用真实员工账号完成。
- 基础设施现状：100 GiB 数据盘、MySQL 数据目录、两个私有 ACL OSS Bucket、ECS RAM Role、主站及 CDN HTTPS、正式 DNS 和每日本地备份均已投入使用；`static.yryhx.cn` 已通过 CDN 同账号私有 OSS 回源提供公共静态资源。

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

## 最近完成：PR #3 云服务器部署

以下事项已于 2026-08-12 按顺序完成，保留为可复核的上线记录，不再属于待办任务。

| 完成 | ID | 状态 | 任务 | 原因/依赖 | 完成证据 |
| --- | --- | --- | --- | --- | --- |
| [x] | DEPLOY-001 | Done | 核对私有 Bucket 的实际 CORS，并加入 `POST` | PR #3 已将浏览器直传升级为 OSS POST Policy；只保留 `PUT` 会导致线上上传失败 | OPTIONS 预检为 200，允许 `GET, HEAD, PUT, POST`，允许来源 `http://139.224.51.21` |
| [x] | DEPLOY-002 | Done | 执行部署前 MySQL 备份并保存 SHA-256 | 新版本会执行 Flyway V22～V24，部署前必须保留可验证回退点 | `/data/talent-platform/backups/mysql/talent-platform-20260812-230705.sql.gz`；SHA-256 `2eb3114fc2488896c1339871af761bff5ba47629d86d7f3338870d5eab994862` |
| [x] | DEPLOY-003 | Done | 从 `origin/main@491380f` 构建 IP 版生产 JAR | ICP/CDN 尚未就绪，本轮不能部署引用 `static.yryhx.cn` 的 CDN 构建 | `AssetBase=/`；前端 12 项、后端 85 项测试通过；JAR SHA-256 `115834bd12901f98c281783a270a7aa46d0293e64c3d1786219847f24cf3e0a0` |
| [x] | DEPLOY-004 | Done | 上传新 JAR、部署脚本及配置模板到 ECS | GitHub 合并不会自动把本地文件发送到服务器 | ECS JAR 哈希与本机构建产物一致；生产验收脚本已同步并通过 Linux 语法检查 |
| [x] | DEPLOY-005 | Done | 使用 `update-app.sh` 更新应用并触发 Flyway | 让考试自动发布、课件学习和安全直传代码真正运行 | 2026-08-12 23:09 应用容器重建成功，`/actuator/health` 为 `UP`，Flyway 为 V24 |
| [x] | DEPLOY-006 | Done | 执行 OSS、课件、附件及核心业务冒烟测试 | 单纯健康检查不能证明上传、预览、权限和自动成绩流程可用 | 四个生产脚本全部通过；考试结束前隐藏成绩、结束后可见的后端回归测试通过 |
| [x] | DEPLOY-007 | Done | 记录线上版本并验证回滚材料 | 后续必须能够判断服务器到底运行哪个提交 | 回滚 JAR：`/data/talent-platform/releases/history/pre-491380f-20260812-230714/talent-platform.jar`；回滚 JAR SHA-256 `272cb2af3f1ae493992bd41379ac89b8c83bec24891e04b469d28ffb55a15a87` |

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

## 综合评价优化发布

| 完成 | ID | 状态 | 任务 | 验收标准 |
| --- | --- | --- | --- | --- |
| [x] | DEPLOY-008 | Done | 发布月度评价工作台与多来源加权 | PR #9 已合并；ECS 运行 `main@90d09a4`；JAR SHA-256 `7ead9945d8a0335741c2df2db4c04a7bdf7cff1b9e4eb1b69576eee02d21af4a`；Flyway V25、健康、员工队列、评价来源及模板接口均通过 |
| [x] | DEPLOY-009 | Done | 发布评分任务与评分人编排 | PR #11 已合并；功能提交 `e53e4b0` 已部署；前端 12 项、后端 92 项测试及生产构建通过；JAR SHA-256 `d46b3452fffd51baa5bde2c6c1abdbe2a243434cea870a416f1a93f684b4fffb`；Flyway V26、两张评分任务表、健康、页面和未登录权限边界均通过 |
| [x] | DEPLOY-010 | Done | 发布任务优先的全员/批次/板块评分人配置 | PR #12 已合并并部署 `main@3a611a44`；前端 12 项、后端 96 项测试、生产构建、生产依赖审计、隔离库 V27 和浏览器主链路通过；生产备份 SHA-256 `7aad2d95…cd64`，JAR SHA-256 `7ef1009d…5ce5`，健康、V27、表结构、页面和未登录权限边界均通过 |

## 正式域名与 CDN 上线任务

| 完成 | ID | 状态 | 任务 | 阻塞原因 | 解除阻塞后的验收标准 |
| --- | --- | --- | --- | --- | --- |
| [x] | ICP-001 | Done | 备案系统提交管局 | 外部等待条件已经解除 | 2026-08-17 备案控制台显示管局审核通过 |
| [x] | ICP-002 | Done | 完成管局审核和短信核验 | 外部等待条件已经解除 | 已取得主体备案号 `湘ICP备2026035229号`，网站备案号 `湘ICP备2026035229号-1`，域名为 `yryhx.cn` |
| [x] | ICP-003 | Done | 在全站底部展示 ICP 备案号并链接工信部备案系统 | 外部条件与部署均已完成 | PR #19 已合并并部署 `main@d0fde6c2`；2026-08-17 生产浏览器验收确认登录页及登录后全局布局显示 `湘ICP备2026035229号-1`，链接为 `https://beian.miit.gov.cn/`，新窗口与安全属性正确 |
| [x] | CDN-001 | Done | 创建并配置 `static.yryhx.cn` CDN 域名 | 外部条件与配置均已完成 | 同账号 OSS 私有 Bucket 回源、`cert-70iod6` HTTPS 证书、HTTP→HTTPS 301、TLS 1.2/1.3、Gzip 与 `/assets/` 一年缓存均已启用；TLS 1.0/1.1 已关闭 |
| [x] | DNS-001 | Done | 添加根域名、`www` 和 `static` 正式 DNS 记录 | 外部条件与配置均已完成 | `@` A=`139.224.51.21`，`www` CNAME=`yryhx.cn`，`static` CNAME=`static.yryhx.cn.w.kunlunaq.com`；阿里公共 DNS 与 1.1.1.1 均解析成功，HTTP/HTTPS 跳转实测通过 |
| [x] | CDN-002 | Done | 从最新业务代码构建并激活 CDN 专用候选 | CDN、证书、DNS 与私有回源均已就绪 | 已激活 `/data/talent-platform/releases/staging/cdn-d0fde6c2-20260817-154555`；JAR SHA-256 `bba246fc633a74829cd049645e876be1960ce1fb422304d65b6d51275edfcc3f`，静态 JS 返回 200、`text/javascript`、一年 immutable，固定节点连续命中 `TCP_MEM_HIT` |
| [ ] | GO-LIVE-001 | In Progress | 完成正式域名全链路验收并观察 24 小时 | 自动观察已完成：日志 `/data/talent-platform/monitoring/go-live-20260817.tsv` 共 279 行，其中 278 次 `PASS`、0 次 `FAIL`、1 次 `COMPLETE`；定时器已自动停用。2026-08-18 19:21 复核应用 `UP`、MySQL `healthy`、CDN `TCP_HIT`、数据盘使用率 2%。仅剩登录后的考试、课件和附件流程需要使用真实账号在正式域名下复核 | 使用真实员工/管理员账号完成考试、课件和附件冒烟测试且无异常后改为 `Done`；再评估是否提高 DNS TTL |
| [ ] | MPS-001 | Blocked | 完成公安联网备案并在网站底部展示公安备案号 | 正式域名已上线；等待项目负责人完成公安备案并提供公安机关审核通过的真实备案号 | 将真实公安备案号及 `https://beian.mps.gov.cn/` 链接加入同一合规页脚，更新本文档并验证公开页面可见；不得提前展示占位备案号 |

## 运行安全与可靠性

| 完成 | ID | 优先级 | 状态 | 任务 | 完成标准 |
| --- | --- | --- | --- | --- | --- |
| [ ] | SEC-001 | P1 | Ready | 重新执行后端完整依赖漏洞扫描 | OWASP Dependency-Check/NVD 或等效扫描成功完成，结果归档；高危漏洞为 0 或已有明确处置 |
| [ ] | SEC-002 | P1 | Ready | 关闭临时公网 IP 与未知 Host 的反向代理入口 | HTTP 默认虚拟主机不再把未知 Host 转发给应用，HTTPS 未知 SNI/Host 被拒绝；`yryhx.cn` 和 `www.yryhx.cn` 的 HTTPS、301 跳转、健康接口及业务请求回归通过 |
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
| [ ] | UX-002 | P3 | Backlog | 增加正式站点 favicon 并放行公开访问 | 真实浏览器不再因 `/favicon.ico` 返回 401 产生控制台错误，图标可由主站正常缓存加载 |

## 维护规则

1. 每次开始任务前先核对依赖条件和当前线上证据，不能仅凭旧文档判断状态。
2. 状态变化时同步更新本文件；任务完成后勾选复选框、改为 `Done`，并补充日期和证据路径或命令结果。
3. 涉及代码、接口或权限的任务同时更新 [task-board.md](task-board.md)、[api-contract.md](api-contract.md) 或 [permissions-matrix.md](permissions-matrix.md)。
4. 涉及 ECS、OSS、CDN、DNS、证书、数据盘或备份的任务同时更新 [aliyun-deployment.md](aliyun-deployment.md)。
5. 不在 Markdown 中记录密码、私钥、AccessKey、完整签名 URL 或验证码。
