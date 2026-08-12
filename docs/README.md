# 项目文档目录

本目录保存项目协作中需要持续维护的设计、需求、接口和任务文档。GitHub 仓库首页默认只展示根目录 `README.md`，因此 `docs/` 下的文档需要通过本索引或 README 中的链接进入。

## 文档清单

| 文档 | 用途 | 适合阅读对象 |
| --- | --- | --- |
| [requirements.md](requirements.md) | 固化阶段 1 MVP 的需求口径、目标用户、核心业务闭环和当前已支持范围。 | 产品负责人、开发者、测试人员、新协作者 |
| [api-contract.md](api-contract.md) | 记录当前 `/api/v1` 接口约定，包括认证、响应结构、错误码、分页和主要接口。 | 前端开发、后端开发、接口联调人员 |
| [permissions-matrix.md](permissions-matrix.md) | 说明角色、权限点、数据范围、特殊规则和前端路由权限。 | 后端开发、前端开发、测试人员、权限相关需求负责人 |
| [task-board.md](task-board.md) | 轻量任务表，用于在没有细化 GitHub Project 前追踪任务状态。 | 项目维护者、开发者 |
| [pending-tasks.md](pending-tasks.md) | 汇总当前未完成任务、PR #3 云端部署顺序、外部阻塞条件和逐项验收证据。 | 项目负责人、部署运维人员、后续执行者 |
| [codebase-guide.md](codebase-guide.md) | 梳理系统架构、核心业务链路，并说明每个源码、配置、迁移和测试文件的职责。 | 新加入或接手项目的开发者、维护者 |
| [aliyun-deployment.md](aliyun-deployment.md) | 记录 ECS、私有 OSS 签名传输、公共 OSS＋CDN、`yryhx.cn`、迁移回退和费用边界。 | 项目维护者、部署运维人员 |

## 维护规则

- 需求边界变化时，同步更新 `requirements.md`。
- 新增、删除或修改接口时，同步更新 `api-contract.md`。
- 角色、权限点、数据范围、菜单入口或权限校验变化时，同步更新 `permissions-matrix.md`。
- 新增协作任务、任务状态变化或阶段性验收完成时，同步更新 `task-board.md`。
- 当前上线任务、外部阻塞条件或执行证据变化时，同步更新 `pending-tasks.md`。
- 新增、删除或调整源码职责、模块关系或构建方式时，同步更新 `codebase-guide.md`。
- 云端架构、部署脚本、运行地址或 OSS/ESA 接入条件变化时，同步更新 `aliyun-deployment.md`。
- 文档变化应和对应代码变化放在同一个 Pull Request 中，避免代码和说明脱节。

## 与 README 的关系

- `README.md` 是仓库首页，负责说明项目是什么、如何运行、如何协作和如何发布。
- `CONTRIBUTING.md` 是协作规范，负责说明 GitHub 分支、提交、推送、PR 和版本管理流程。
- `docs/` 是项目细节文档，负责承载需求、接口、权限和任务等更详细内容。
