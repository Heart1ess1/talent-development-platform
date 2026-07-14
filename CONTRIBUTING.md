# 协作开发指南

本文档写给接手本项目的新协作者，也适合刚开始使用 GitHub 的成员。目标是让每个人按同一套规则开发、提交、审核和发布，避免覆盖别人代码、提交本地产物或把敏感信息上传到 GitHub。

## 先理解几个概念

- Git：本机的版本管理工具，记录每次代码修改。
- GitHub：远程代码托管平台，用来同步代码、多人协作、代码审查和发布版本。
- Repository（仓库）：保存项目代码和版本历史的地方，本项目仓库是 `Heart1ess1/talent-development-platform`。
- Branch（分支）：从主线分出来的开发线。新功能、修复和实验都应在单独分支完成。
- Commit（提交）：一次明确的代码变更记录。
- Push（推送）：把本机提交上传到 GitHub。
- Pull（拉取）：把 GitHub 上的新代码同步到本机。
- Pull Request（PR）：请求把某个分支的修改合并到 `main`，也是代码审查入口。
- Release（发布）：在 GitHub 上发布可下载的正式版本包，不等同于普通代码提交。

## 分支规则

`main` 是稳定主分支，代表当前可继续开发和发布的基线。不要直接在 `main` 上开发日常功能。

推荐分支命名：

```text
feature/short-description      # 新功能
fix/short-description          # 缺陷修复
docs/short-description         # 文档修改
refactor/short-description     # 重构
release/v0.1.0                 # 发布准备
```

示例：

```text
feature/exam-import
fix/login-token-expiry
docs/github-workflow
```

## 第一次参与项目

如果还没有本地代码，先克隆仓库：

```powershell
git clone https://github.com/Heart1ess1/talent-development-platform.git
cd talent-development-platform
```

配置你的提交身份，只需要在本机配置一次：

```powershell
git config user.name "你的名字或GitHub用户名"
git config user.email "你的邮箱"
```

确认当前状态：

```powershell
git status
```

如果看到 `nothing to commit, working tree clean`，说明当前没有未提交修改。

## 日常开发流程

每次开始开发前，先同步主分支：

```powershell
git switch main
git pull
```

创建自己的功能分支：

```powershell
git switch -c feature/your-change-name
```

修改代码后查看变化：

```powershell
git status
git diff
```

把要提交的文件加入暂存区：

```powershell
git add README.md
```

如果确认本次所有未忽略文件都要提交，也可以使用：

```powershell
git add .
```

提交代码：

```powershell
git commit -m "Add employee import validation"
```

推送分支到 GitHub：

```powershell
git push -u origin feature/your-change-name
```

然后在 GitHub 页面创建 Pull Request，请求合并到 `main`。

## 提交信息规则

提交信息应简短说明本次修改做了什么。推荐使用英文动词开头，也可以使用清晰中文。

推荐：

```text
Add employee import validation
Fix login token expiration handling
Update GitHub collaboration docs
修复考试提交后的状态刷新问题
```

不推荐：

```text
update
fix
改了一些东西
临时提交
```

一个提交最好只做一类事情。不要把“修复登录问题、重构评价模块、修改 README、上传发布包”混在同一个提交里。

## Pull Request 规则

创建 PR 前，请确认：

- 分支是从最新 `main` 创建或已经同步过 `main`。
- 只包含和本次任务相关的改动。
- 没有提交 `data/`、`release/`、`outputs/`、`node_modules/`、`target/`、`dist/` 等本地产物。
- 没有提交 `.env`、真实密码、密钥、数据库备份或用户数据。
- 已运行与本次修改相关的测试。

PR 描述应说明：

- 为什么改。
- 改了什么。
- 如何验证。
- 是否涉及数据库迁移、权限、安全、发布包或环境变量。

本仓库已提供 `.github/PULL_REQUEST_TEMPLATE.md`，创建 PR 时 GitHub 会自动带出检查项。

## 合并规则

建议由项目维护者在 GitHub 页面合并 PR。合并前至少检查：

- 代码方向是否符合需求。
- 测试是否通过。
- 是否有不该提交的文件。
- 数据库迁移是否合理。
- README 或协作文档是否需要同步更新。

合并后，开发者本地同步最新 `main`：

```powershell
git switch main
git pull
```

如果功能分支已经合并，可以删除本地分支：

```powershell
git branch -d feature/your-change-name
```

GitHub 上的远程分支可以在 PR 合并页面删除。

## 如何避免覆盖别人代码

开发前先拉取：

```powershell
git switch main
git pull
```

开发过程中如果别人已经合并了新代码，可以在自己的分支上同步：

```powershell
git fetch origin
git merge origin/main
```

如果出现冲突，Git 会标出冲突文件。处理原则：

1. 打开冲突文件，查找 `<<<<<<<`、`=======`、`>>>>>>>`。
2. 保留正确内容，删除冲突标记。
3. 运行测试或至少重新启动相关服务。
4. 提交冲突解决结果。

```powershell
git add 冲突文件
git commit -m "Resolve merge conflicts"
```

如果不确定冲突内容，先不要强行提交，联系项目维护者一起确认。

## 哪些文件不要提交

不要提交：

- `.env` 和任何环境专用配置
- 真实密码、Token、Access Key、Secret Key、JWT Secret
- `data/` 运行数据和上传文件
- `release/` 发布包目录
- `outputs/` 临时输出
- `frontend/node_modules/`
- `.pnpm-store/`
- `frontend/dist/`
- `backend/target/`
- `launcher/bin/`、`launcher/obj/`、`launcher/publish/`
- 数据库备份、生产日志、真实用户 Excel

如果发现敏感信息已经提交，不能只删除文件后继续提交，应立即通知维护者，因为 Git 历史中仍可能保留该信息。

## 验证要求

后端修改优先运行：

```powershell
cd backend
mvn test
```

前端修改优先运行：

```powershell
cd frontend
npm run test
npm run build
```

如果只是文档修改，可以不运行完整测试，但 PR 中要说明“仅文档修改，未运行测试”。

## 数据库迁移规则

数据库迁移脚本位于：

```text
backend/src/main/resources/db/migration/
```

规则：

- 新增迁移脚本，不修改已经合并到 `main` 的历史迁移。
- 文件名遵循 Flyway 版本顺序，例如 `V5__add_training_score.sql`。
- 字段删除、数据迁移、默认值变化要在 PR 中明确说明。
- 生产环境升级前必须备份数据库。

## 发布版本规则

发布包不提交到 Git 仓库。发布流程应使用 GitHub Releases。

建议流程：

1. 确认 `main` 是干净且已验证的版本。
2. 生成本地发布包目录 `release/TalentPlatform`。
3. 压缩为 zip，例如 `TalentPlatform-v0.1.0.zip`。
4. 在 GitHub 创建 Release，标签名使用 `v0.1.0` 这类格式。
5. 上传 zip 作为 Release 附件。
6. 在 Release 说明中写明新增功能、修复内容、升级注意事项和已知问题。

版本号建议遵循：

```text
v主版本.次版本.修订版本
```

示例：

- `v0.1.0`：第一个可试用版本
- `v0.2.0`：新增一组功能
- `v0.2.1`：只修复缺陷
- `v1.0.0`：达到正式稳定版本

## 常用命令速查

查看当前状态：

```powershell
git status
```

查看修改内容：

```powershell
git diff
```

切换到主分支：

```powershell
git switch main
```

拉取最新代码：

```powershell
git pull
```

创建并切换分支：

```powershell
git switch -c feature/name
```

提交修改：

```powershell
git add .
git commit -m "Describe your change"
```

推送当前分支：

```powershell
git push -u origin feature/name
```

查看提交历史：

```powershell
git log --oneline --decorate -10
```

## 协作底线

- 先同步，再开发。
- 一事一分支，一事一提交或少量相关提交。
- 代码进 `main` 前走 PR。
- 发布包走 GitHub Releases，不走普通提交。
- 敏感信息永远不进 Git。
- 不确定时先问，不要用强制推送或删除历史来“试一试”。
