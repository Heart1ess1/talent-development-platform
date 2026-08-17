# 阿里云 ECS 部署文件

本目录保存已经在 Alibaba Cloud Linux 4、Docker 24 和 Docker Compose 2.26 上验证过的生产部署模板。真实密码、数据库数据、上传文件和构建后的 JAR 均不得提交到 Git。

| 文件 | 用途 |
| --- | --- |
| `Dockerfile` | 基于 Temurin 17 构建应用运行镜像，并安装 Word/PPT 转 PDF 所需的 LibreOffice 与中文字体。 |
| `docker-compose.yml` | 在单台 ECS 上运行 MySQL 8.4、Spring Boot 和 Nginx，并将持久数据绑定到 `/data/talent-platform/`，同时传入私有/公共 OSS 与 CDN 配置。 |
| `nginx.conf` | 反向代理 Spring Boot，并为带内容哈希的 `/assets/*` 输出可供 ESA/CDN 复用的长期缓存头。 |
| `nginx-domain.conf` | OSS/CDN 分层后的网站源站配置；只承载 HTML 与 API，域名为 `yryhx.cn`/`www.yryhx.cn`。 |
| `nginx-https.conf` | 主站 HTTPS 配置：`yryhx.cn` 承载应用，`www.yryhx.cn` 通过独立证书跳转到主域名。 |
| `install-https-certificates.sh` | 校验证书域名、有效期和密钥匹配后安装主站证书；重建 Nginx、执行 HTTPS 探活并在失败时回滚。 |
| `test-install-https-certificates.sh` | 使用临时证书和替身命令验证证书安装成功、危险目录拒绝、密钥不匹配拒绝及失败回滚，不改动生产环境。 |
| `install-runtime.sh` | 在 Alibaba Cloud Linux 4 安装免费的 Docker 与 Compose 运行时。 |
| `docker-talent-data.conf` | 让 Docker 服务依赖 `/data` 挂载，防止数据盘异常时在系统盘静默创建同名目录。 |
| `bootstrap.sh` | 首次生成随机密钥和生产环境变量，然后拉取镜像并启动服务。 |
| `update-app.sh` | 原地更新 JAR，只重建应用容器，不触碰 MySQL 数据卷。 |
| `backup-mysql.sh` | 将 MySQL 逻辑备份压缩写入数据盘，校验 gzip 和 SHA-256，并默认保留 14 天。 |
| `talent-platform-backup.service` / `.timer` | 每天 02:30 后随机延迟不超过 10 分钟运行数据库备份，停机错过后补跑。 |
| `verify.sh` | 检查容器、健康接口、Flyway 迁移和用户数据；首次改密前可选择验证初始登录。 |
| `build-production.ps1` | 用 CDN AssetBase 构建前端，执行前后端测试并输出生产 JAR 哈希。 |
| `sync-public-assets.sh` | 使用 ECS RAM Role 把内容哈希静态资源上传到公共资源 Bucket。 |
| `prepare-cdn-release.sh` | 在 `/data` 已挂载时同步内容哈希静态资源到 OSS，在数据盘生成带 JAR/资源哈希与发布元数据的候选，并原子更新 `staging/cdn-ready`。 |
| `activate-cdn-release.sh` | CDN 域名和 HTTPS 可访问后校验数据盘中 `staging/cdn-ready` 指向的候选资源、把当前版本备份到数据盘发布历史、启用 CDN 专用 JAR；也可显式传入候选目录，失败自动回滚。 |
| `migrate-local-files.sh` | 把历史文件卷复制到私有 Bucket，并把头像复制到公共 Bucket；不删除源文件。 |
| `ram-policy.json` | ECS RAM Role 的最小 OSS 对象读写权限模板；Bucket 名变更时必须同步修改。 |
| `oss-private-cors.json` | 浏览器直传和签名读取所需的私有 Bucket CORS 模板。 |
| `oss-public-cors.json` | CDN 公共资源域名所需的只读 Bucket CORS 模板。 |
| `check-production-readiness.ps1` | 从本机检查域名解析、HTTPS、ECS RAM Role 与 OSS/CDN 环境变量，不输出密钥。 |
| `verify-oss-switch.sh` | 切换到 OSS 后在 ECS 内核验 RAM Role、两个 Bucket、容器与健康检查。 |
| `smoke-oss-app.sh` | 通过真实头像接口验证公共 Bucket 的上传、读取和删除，并清理临时对象。 |
| `smoke-private-courseware.sh` | 验证私有课件签名直传、完成登记、水印预览、原件下载拦截和删除清理。 |
| `smoke-private-attachment.sh` | 验证私有任务附件签名直传、鉴权后的限时签名下载及删除清理。 |

课件原件始终存入私有 Bucket。应用容器把 Word、PDF、PPT、OFD 和图片统一渲染成带当前员工姓名/工号水印的 PNG 页面；转换缓存位于数据盘 `/data/talent-platform/preview-cache`，不会向浏览器暴露原文件。

完整操作顺序、费用边界和 OSS/ESA 后续接入方式见 [../../docs/aliyun-deployment.md](../../docs/aliyun-deployment.md)。
