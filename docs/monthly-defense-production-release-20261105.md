# 月度答辩上线记录（2026-11-05）

## 发布版本

- 功能 PR：https://github.com/Heart1ess1/talent-development-platform/pull/79 （已合并）。
- 主分支合并提交：`3c9e4dc6a76eefd9867a2d474e52544db0a6c723`；构建源提交：`d83afd141829684363deb876e812b14ecb99c0dd`。合并后的 backend/frontend/deploy 与构建源无差异。
- 线上地址：https://yryhx.cn ，约北京时间 2026-11-05 10:10 完成激活。
- JAR SHA256：`f9d192c97c26fbde0572fc399e5fc7df297d406e02978c15eb329b73b116f97b`。
- 候选目录：`/data/talent-platform/releases/staging/cdn-monthly-defense-3c9e4dc-20261105`，含 RELEASE-METADATA、SHA256SUMS、97项 ASSET-SHA256SUMS。

## 备份

- 数据库：`/data/talent-platform/backups/mysql/talent-platform-20261105-100916.sql.gz`，gzip完整性和校验文件均通过。
- 数据库备份 SHA256：`856a17b999de0e705d2ec6a266295e424a6ba30f5c6efea069c696daf6b9a092`。
- 原应用与环境：`/data/talent-platform/releases/history/cdn-activation-20261105101042-1150231`。
- 原 JAR SHA256：`f3cab5a691bcbee3a3ce61544fd7c989402bdd5be2385cc5dfa2f88fe7bd52a4`。

## 生产检查（已验证）

| 检查 | 结果 |
| --- | --- |
| Spring Boot健康与发布就绪脚本 | UP；ready=true |
| app / web / db | running；MySQL healthy |
| Flyway | V49、V50成功，当前V50，失败迁移0 |
| 员工数量 | 升级前后186 |
| 历史答辩成绩 | 升级前后102，均保留task_id=NULL |
| 历史答辩导入批次 | 升级前后1 |
| 旧成绩核心字段校验 | 升级前后一致：084b63dab2f8de343d28955a83da3b68b913612ff168e35af3ca8c99ec52654a |
| 月度/季度汇总 | 升级前后均0，未生成测试业务数据 |
| 新答辩任务 | 0；没有把旧成绩自动映射或回填 |
| 最近应用错误 | 新容器日志 ERROR 0；迁移正常完成 |
| 页面路由 | /、/login、/evaluation/defenses、/evaluation/templates、/evaluation/monthly、/evaluation/results均200 |
| 未登录API | 新任务查询及季度接口返回401 |
| CDN | 主JS/CSS、答辩页JS/CSS、月评页JS/CSS均200且内容类型正确；解码gzip后SHA256均与构建文件一致 |
| OSS匿名直连 | 403 |
| HTTPS与跳转 | 证书有效，HTTP与www跳转正常 |
| 公网浏览器 | 未登录访问答辩页正常跳转登录，登录表单渲染完成 |

激活时出现短暂502，自动重试后健康恢复。公网浏览器控制台仅发现 favicon.ico 返回401；未发现主应用资源加载失败。未将这一公共页面检查表述为登录后的业务验收。

## 本地验证与待验收边界

本地测试、真实MySQL迁移和完整管理员浏览器流程见 [月度答辩验收记录](monthly-defense-qa-20261105.md)。后端260项（217执行、43跳过）和前端75项通过，类型检查及生产构建通过；GitHub仓库未配置CI检查。

生产未提供可用正式账号，本次没有重设账号、伪造登录令牌或向正式库写入测试成绩。管理员、培训管理员、导师和员工的正式登录业务验收仍待完成；本地自动测试和浏览器流程不能替代此项。

## 管理员切换操作

1. 进入评价模板，复制或新建月度模板；启用答辩时重新分配六项权重，合计100%，关闭项为0。
2. 应用到培养批次与生效月份并发布方案。方案向后沿用；下一个无答辩月份需发布关闭答辩的方案。
3. 答辩评价→新建任务，指定月份、满分和明确名单→保存草稿→启用。答辩日期不决定计分月份。
4. 录入最终成绩并发布，或按模板导入、纠错、重新校验后整批发布。
5. 月度评分核对来源并生成月评，结果中心发布。启用答辩但无正式成绩时不能发布。
6. 更正已发布答辩需填修订原因，随后到关联修订审核核对并成组发布；原正式月评在审核前保持有效。

历史季度数据、旧答辩成绩保留在数据库，业务入口不再展示。上线没有自动创建任务、回填月份或替管理员发布新方案。

## 回滚

恢复旧JAR和环境配置可使用上述应用备份；V49/V50不是通过替换JAR自动撤销。若需数据库恢复，先评估部署后业务写入并使用已验证备份，避免丢失新数据。不得删除旧记录或修改已执行Flyway迁移。
