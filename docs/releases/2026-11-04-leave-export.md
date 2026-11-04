# 2026-11-04 请假记录导出上线

北京时间 2026-11-04 09:49 发布到 https://yryhx.cn。[PR #77](https://github.com/Heart1ess1/talent-development-platform/pull/77) 已合并，部署源码提交 `619a1a30271549424f504cbac89f6df32ef87696`。本记录只补充发布证据，不修改线上功能。

## 上线内容

“我的请假”和“请假审批”右上角新增“导出记录”，将当前页签与筛选条件下的全部匹配版本导出为 Excel，包含跨页数据。24 列涵盖员工、场景、性质、起止时间、计假天数、地点与原因、指定/实际审批人、审批和单据状态、实际返回信息。导出与列表共用权限和筛选条件，记录导出视图与条数审计。

审批权限、候选审批人员及转交规则保持现状。无需数据库迁移或新增环境变量，Flyway 维持 V48。培训管理员核查与隔离环境完整流程见 [验收记录](../qa/leave-review-export-20261104.md)。

## 验证

- 基于合并提交执行生产构建：前端 25 文件、75 项测试通过，Vue 类型检查及 CDN 生产构建通过；后端 247 项，241 项通过、6 项其他按需测试跳过，失败/错误 0。其中 24 项真实 MySQL 请假集成测试通过，覆盖培训管理员转交后审批、105 条跨页导出、筛选/权限范围、匿名 401、Excel 响应和回读。
- 发布前完成生产数据库备份，gzip 完整性和 SHA-256 回读通过。上传 97 个静态资源，候选与运行 JAR 哈希一致。切换启动时出现 3 次短暂 502，自动重试后恢复 `UP`，未触发回退。
- 公开 HTTPS 健康检查 `UP`、发布就绪 `ready: true`，MySQL `healthy`。Flyway 最新 V48、失败迁移 0；发布后日志检查窗口内错误/异常匹配数 0。
- 请假、账号、人员台账 SPA 直接访问均为 200。导出接口 `view=all/pending/mine` 未登录均返回 401；请假列表未登录返回 401，OSS 匿名访问返回 403。
- 主站 HTML 引用本次主 JS；CDN 主 JS、主 CSS、请假 JS/CSS、登录 JS 共 5 项均为 200，按响应 Content-Encoding 解压后 SHA-256 与本地构建一致。线上请假 JS 确认包含 `/leave-requests/export`。
- 发布前后员工 186、账号 191、任务安排 358、请假 4、待审批版本 3，数量一致。未代真实员工审批或变更生产职责。
- 隔离环境在开发验收中已通过正常浏览器登录、转交后同意/驳回、Excel 实际下载及独立回读。此次生产浏览器验收因工具无法可靠识别 Windows 当前浏览器 URL 而停止；未改用其他方式绕过该限制，未使用生产账号进行登录后下载，真实账号页面导出仍待验收。该边界不影响已完成的产物部署与公开接口/资源验证。

## 备份、产物与回退

| 项目 | 证据 |
| --- | --- |
| 新 JAR SHA-256 | `f3cab5a691bcbee3a3ce61544fd7c989402bdd5be2385cc5dfa2f88fe7bd52a4` |
| 原 JAR SHA-256 | `0f807188d79c56ed6efc968e360a74e9f48bb2ddc90564828a0b833b44a4ce0d` |
| 数据库备份 | `/data/talent-platform/backups/mysql/talent-platform-20261104-094801.sql.gz` |
| 备份大小 | 517797 字节 |
| 备份 SHA-256 | `9963c492a327ae94d6e30bb33c261a5156214e5ffc77235a360490147846dd34` |
| 发布候选 | `/data/talent-platform/releases/staging/cdn-leave-export-619a1a3-20261104` |
| 原应用及环境回退目录 | `/data/talent-platform/releases/history/cdn-activation-20261104094938-985788` |
| CDN 主 JS | `https://static.yryhx.cn/assets/index-DBPD82VU.js` |
| CDN 主 JS SHA-256 | `0a88ca41fbeebeca4d5429ea05222ca11108d9e5fbd1317d5ccbd48dc094fd91` |
| CDN 请假 JS | `https://static.yryhx.cn/assets/LeaveRequestsView-BaSG2O6l.js` |
| CDN 请假 JS SHA-256 | `33a1693b4cbab9be79bf50f7d47213b17b2043c6c9e14041d6035ce271c14d1d` |

如需回退，恢复上述回退目录中的 `.env` 与 `talent-platform.jar` 至 `/opt/talent-platform/`，仅重建应用容器，并重新执行健康、哈希、资源和接口权限验证。无需数据库降级。

本地运行证据位于工作区 `.codex-tmp/export-production-build.log`、`export-backup.log`、`export-activation.log`、`export-verification.log`、`export-readiness.json`、`export-cdn-verification.json`，不纳入 Git。生产备份保留在服务器。
