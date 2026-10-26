# 2026-10-26 场次人员安排生产发布记录

北京时间 2026-10-26 13:35 激活生产版本，地址为 https://yryhx.cn。最终健康状态 `UP`，生产就绪检查 `ready: true`，已使用授权管理账号完成登录后的场次人员安排页面验收。

## 发布内容与版本

- 人员安排功能：[PR #67](https://github.com/Heart1ess1/talent-development-platform/pull/67)，合并提交 `a2087cb146cc48e781cb2426365c894e997de6c8`。
- 课程页面直达修复：[PR #68](https://github.com/Heart1ess1/talent-development-platform/pull/68)，最终运行源码提交 `3c1907f30cd36e1acf47bd3280ac0da498548637`。
- 改为居中弹窗、服务端完整筛选分页、跨页保留、本页/全部结果全选、人员草稿增删与统一保存、签到保护和并发冲突检测。
- 发布前实测 `/courses/sessions` 直接访问返回 404，补齐课程二级 SPA 路由转发并增加 6 项回归测试；发布后 5 个课程页面直达均为 200。
- 不新增数据库迁移或环境变量，Flyway 保持 V47，不重算历史数据。

实现阶段说明和独立数据库验收详见[本地验收记录](../qa/session-assignment-20261026.md)。

## 构建、备份及回退材料

从合并提交导出独立源码，前端用 `VITE_ASSET_BASE=https://static.yryhx.cn/` 构建。最终 SPA 修复只变更后端；已通过 Git 差异确认前端源码完全相同，再将已验证的 CDN 产物复制到最终独立源码，执行后端完整测试和打包。

| 材料 | 值 |
|---|---|
| 生产 JAR SHA-256 | `72693f39a67fc738c09124483fccfef90399383afda69774e65bd2998a82b496` |
| 发布前 JAR SHA-256 | `b4c993618ab9435fbd78a2dc6538d7e2320c104df8f628898598698904545287` |
| 发布候选 | `/data/talent-platform/releases/staging/cdn-session-assignment-3c1907f-20261026` |
| 数据库备份 | `/data/talent-platform/backups/mysql/talent-platform-20261026-133301.sql.gz` |
| 备份 SHA-256 | `79fd8204934b55f69e78121096be8de3f0b7efe742240aea41bf8e66da1c8d3e` |
| 备份大小 | 379681 字节；gzip 和 SHA-256 校验通过 |
| 原 JAR 与环境配置备份 | `/data/talent-platform/releases/history/cdn-activation-20261026133544-3750104` |
| CDN 主资源 | `https://static.yryhx.cn/assets/index-U8bNlSXi.js` |
| 主资源 SHA-256 | `1a0a5c788608a3d6865486163fe2b9fec93d9ade6f0dd8e6c2c41733e95054df` |
| 场次页面资源 | `https://static.yryhx.cn/assets/CourseSessionsView-tAvHQHVZ.js` |
| 场次资源 SHA-256 | `d9aaae250c20db5580966105c6b73ca4851385ffbb883ff5848e84a2a0bf98b9` |

先校验数据库备份及当前运行 JAR，再上传并校验新 JAR、同步 92 个静态资源，运行既有 `prepare-cdn-release.sh` 和 `activate-cdn-release.sh`。候选元数据记录源码提交和 PR 编号；本地、候选与当前运行 JAR 哈希一致。

应用重建时有 3 次短暂 502，激活脚本重试后恢复 `UP`，没有触发回退。原 JAR、环境文件和数据库备份均保留在服务器受控位置；失败回退沿用既有激活脚本。

## 自动化及上线检查

| 验证 | 结果 |
|---|---|
| 前端测试 | 25 个文件、75 项通过；类型检查及 CDN 生产构建通过 |
| 最终后端构建 | 200 项常规测试通过，失败/错误为 0；6 项显式连接测试库的集成测试默认跳过 |
| 独立 MySQL 8.4 | 实现阶段单独执行的 6 项集成测试通过，覆盖真实回滚、并发签到及权限保护；未在生产运行数据库写入测试 |
| 生产数据库 | Flyway V47，失败迁移数 0；MySQL healthy |
| 生产容器和健康 | 应用、Nginx 正常，HTTPS 健康 `UP`，`ready: true` |
| 课程页面直达 | `/courses/sessions`、`manage`、`materials`、`my`、`learning` 均为 200 |
| 未登录边界 | 人员状态、候选查询、全部结果解析和统一保存接口均返回 401 |
| CDN | 主 JS、CSS、场次 JS 返回正确内容类型和长期缓存，内容 SHA-256 与本地产物一致；CSS 按 `Content-Encoding: gzip` 解压后核验 |
| OSS | 原始 Bucket 地址匿名访问为 403，继续保持私有回源 |
| 近期应用日志 | 上线后两次核查窗口内 ERROR/Exception/迁移失败匹配数均为 0 |

部署前后及登录验收后，员工档案 275、场次 5、人员安排 0、签到 0 均保持一致。

## 生产实际账号验收

通过已有授权管理账号的生产会话，操作真实前后端和生产名单完成以下检查：

- 可新增候选人员共 233 人，超过旧版前 100 人限制；翻到第 6 页可以选取第 101 人之后的人员。
- 本页全选 20 人、跨页保留并追加为 21 人；全部结果解析与分页总数一致，233 人全选、排除 1 人及清空草稿均正确。
- 11 项筛选显示完整；两个真实班级多选查询返回 135 人，重置后恢复 233 人。
- 1920×1080、1366×768、390×844 界面检查通过；窄屏切换本场名单正常，弹窗和底栏在视口内。
- 验收结束选择放弃变更并关闭，保存请求数量为 0；服务器再次确认人员安排和签到仍为 0。

本次生产尚无现有安排及签到记录，相关历史人员展示、停用保留、签到移除保护、统一写入回滚及冲突恢复已在本地真实 MySQL 环境验证。没有为验收新增生产人员安排。

生产截图、完整检查日志和构建产物保留在本地受控临时目录；未将真实人员名单、账号会话、密钥、环境文件或数据库备份提交到 GitHub。
