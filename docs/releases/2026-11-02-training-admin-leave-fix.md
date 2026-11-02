# 2026-11-02 培训管理员请假审批修正

北京时间 2026-11-02 14:08 部署到 https://yryhx.cn。[PR #75](https://github.com/Heart1ess1/talent-development-platform/pull/75) 已合并，功能合并提交 `d317af589048b82c042dfbee159f03e54601a1aa`。此记录仅补充文档，不改变已部署产物。

## 问题及修正

培训负责人即现有培训管理员（`TRAINING_ADMIN`）。原实现错误地查询独立 `TRAINING_DIRECTOR` 账号，导致已有培训管理员未出现在员工的审批人列表中。

现将培训审批层级统一映射到启用的培训管理员：未分配服务站员工的任意时长请假、已分配服务站员工的一天以上请假，以及后续审批资格和责任转交，均使用这一候选规则。仅被选中的培训管理员可以审批。前端统一显示“培训管理员”，新建账号及角色修改移除独立“培训负责人”选项；历史角色账号仍可查看、维护并改为正确角色。

`TRAINING_DIRECTOR` 保留为历史审批层级编码，与账号角色区分，不需要数据库迁移。培训管理员原有培训管理权限保持正常；请假审批和未销假责任继续阻止直接停用或变更账号角色。

线上只读确认已有 3 个启用的 `TRAINING_ADMIN` 账号，无需另行创建培训负责人账号。此前[首次发布记录](2026-11-02-leave-approval-account-deletion.md)中要求另配独立培训负责人账号的判断已更正。

## 验证

- 后端 243 项中 237 项通过，6 项其他按需集成测试跳过，失败／错误 0；包含 20 项真实 MySQL 请假测试，覆盖不同请假时长、候选角色与启停限制、指定人员审批、转交、变更、销假和账号停用保护。
- 前端 25 文件 75 项通过，类型检查及生产构建通过。
- 隔离环境浏览器通过：未分配服务站员工申请半天请假，手选已有培训管理员并提交；该培训管理员以正常登录流程进入“待我审批”，同意后数据库状态为 `APPROVED`、审批人角色为 `TRAINING_ADMIN`。截图保留于发布工作区 `output/playwright/training-admin-fix-20261102/`，未纳入 Git。
- 生产应用健康 `UP`，就绪检查 `ready: true`；主 JS、CSS、请假、账号、员工目录、登录页共 6 项 CDN 资源均为 200，内容哈希与本次构建一致。
- `/leave-requests`、`/users`、`/employee-directory` 直接访问均为 200；相关 GET 及对不存在 ID 0 的删除接口未登录均为 401。
- Flyway 保持 V48，失败迁移 0；发布后检查窗口内 ERROR／Exception 匹配数 0。发布前后员工 275、账号 280、任务安排 358、请假 0，数量一致；未创建生产测试数据或代真实员工提交请假。
- 正式账号登录后的生产请假页面仍待用户验收：本次没有可复用的有效生产会话，不重置密码或绕过认证；隔离环境的完整流程验证与生产健康检查分别记录。

## 产物、备份及回退

构建提交 `193311c9faff99160fcfaca1d812a89f99c795d3` 与功能合并提交 Git 树相同：`47c8269de7231485e59415ce594ccf6967becbe8`。上传 97 个静态资源，启动时出现 3 次短暂 502，重试后恢复 `UP`，未触发回退。

| 项目 | 证据 |
|---|---|
| 新 JAR SHA-256 | `0f807188d79c56ed6efc968e360a74e9f48bb2ddc90564828a0b833b44a4ce0d` |
| 原 JAR SHA-256 | `bf2b259d56e047ad1f30b4805a8b1bab2b9391c43a66b343b856d2c80c490fa7` |
| 数据库备份 | `/data/talent-platform/backups/mysql/talent-platform-20261102-140736.sql.gz` |
| 备份大小 | 531132 字节；gzip 完整性和 SHA-256 回读通过 |
| 备份 SHA-256 | `1459d6a2314d4275e315a42dd01d3f14b472dc0b7bae1393df5a73dba0b91073` |
| 候选目录 | `/data/talent-platform/releases/staging/cdn-training-admin-193311c-20261102` |
| 原应用及环境回退备份 | `/data/talent-platform/releases/history/cdn-activation-20261102140812-691565` |
| CDN 主 JS | `https://static.yryhx.cn/assets/index-QC6fKQxg.js` |
| 主 JS SHA-256 | `fe0ccca4ed1f4685f3d2f95ff8b1069cb223dae19221069d833e8e5bb84caf09` |

本地、候选和运行 JAR 哈希一致。本次无需数据库降级。备份保留在服务器，本地测试数据库和验证进程在验证结束后清理。本次发布日志位于工作区 `.codex-tmp/training-admin-fix/`，未纳入 Git。
