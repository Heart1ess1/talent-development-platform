# 项目文档目录

本目录保存项目协作中需要持续维护的设计、需求、接口和任务文档。GitHub 仓库首页默认只展示根目录 `README.md`，因此 `docs/` 下的文档需要通过本索引或 README 中的链接进入。

## 文档清单

| 文档 | 用途 | 适合阅读对象 |
| --- | --- | --- |
| [requirements.md](requirements.md) | 固化阶段 1 MVP 的需求口径、目标用户、核心业务闭环和当前已支持范围。 | 产品负责人、开发者、测试人员、新协作者 |
| [api-contract.md](api-contract.md) | 记录当前 `/api/v1` 接口约定，包括认证、响应结构、错误码、分页和主要接口。 | 前端开发、后端开发、接口联调人员 |
| [permissions-matrix.md](permissions-matrix.md) | 说明角色、权限点、数据范围、特殊规则和前端路由权限。 | 后端开发、前端开发、测试人员、权限相关需求负责人 |
| [task-board.md](task-board.md) | 轻量任务表，用于在没有细化 GitHub Project 前追踪任务状态。 | 项目维护者、开发者 |

## 维护规则

- 需求边界变化时，同步更新 `requirements.md`。
- 新增、删除或修改接口时，同步更新 `api-contract.md`。
- 角色、权限点、数据范围、菜单入口或权限校验变化时，同步更新 `permissions-matrix.md`。
- 新增协作任务、任务状态变化或阶段性验收完成时，同步更新 `task-board.md`。
- 文档变化应和对应代码变化放在同一个 Pull Request 中，避免代码和说明脱节。

## 与 README 的关系

- `README.md` 是仓库首页，负责说明项目是什么、如何运行、如何协作和如何发布。
- `CONTRIBUTING.md` 是协作规范，负责说明 GitHub 分支、提交、推送、PR 和版本管理流程。
- `docs/` 是项目细节文档，负责承载需求、接口、权限和任务等更详细内容。
