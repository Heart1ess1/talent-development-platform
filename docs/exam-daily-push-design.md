# 考试中心「每日随机推题」设计方案

- 分支：`feat/exam-daily-push`（基于 `origin/main` 提交 `68a04e4` 创建）
- 状态：设计稿，待确认后再写代码
- 适用系统：一人一画像 talent-platform
- 线上 Flyway 当前到 **V54**，本方案新增迁移从 **V55** 起

---

## 一、一句话结论

在考试中心旁边新增一个**独立的「每日练习」子模块**：题库、Excel 导入、随机抽题、客观题自动判分这些能力直接复用；「按天生成、每人每天一套题、推送时段、完成时限、不漏推」这些新规则用新表和新的定时任务实现。**不改动现有考试计划、试卷、作答、阅卷、防作弊的既有逻辑**，避免把已经上线并锁定规则的成绩模块搅乱。

为什么不让「每日练习」直接套用现有 `exam_plan`？
1. 现有考试有 `exam_paper` 和「试卷总分必须 100 分」的硬校验，每日练习不需要这个约束。
2. 现有考试一场只有一个开放窗口，学员每人一次或几次机会；每日练习是「一个计划 × 31 天 × 每人每天一套」，用现有结构会膨胀出 31 个考试计划、几千条安排记录，后续查成绩、补推都很别扭。
3. 现有考试带全屏防作弊、异常离场倒计时、整场结束才发布成绩等规则；每日练习是轻量练习，规则不同，硬塞进同一套表会让两边逻辑互相牵制。
4. 项目既定规则是「已发布/已锁定数据不可回写」「最小侵入」。新模块可以把历史作答完整冻结，风险最低。

---

## 二、先把名词说清楚（避免后面看混）

| 代码里的名字 | 实际含义 | 本方案是否复用 |
|---|---|---|
| `exam_question_bank` | **题库分组**（如「机动车安全规范题库」） | 复用 |
| `question_bank` | **题目本身**（单选/多选/判断题） | 复用 |
| `exam_paper` | 试卷（手动组卷或随机规则组卷） | 不复用 |
| `exam_plan` | 一次考试计划（有开始/结束时间） | 不复用 |
| `exam_assignment` | 某人被安排参加某场考试 | 不复用 |
| `exam_attempt` | 某人的一次答卷 | 不复用 |
| `exam_answer` | 答卷里每道题的作答 | 不复用 |
| 本方案新增 `exam_daily_*` | 每日练习的计划、参与人、每日作答、题目快照、任务日志 | 新增 |

> 注意：`question_bank` 这个名字容易误解，它是「题目表」，不是「题库表」。后续新增表一律用 `exam_daily_` 前缀，避免再产生歧义。

---

## 三、需求拆解（把一句话需求变成可配置项）

用户原话拆成 8 个能力：

1. **导入约 1 万道题的题库** → 复用现有题库 Excel 导入，但要为万级数据做性能和超时加固。
2. **每天随机抽取一定数量题目** → 每个计划配置「每日题数」，从指定题库/题型/标签中随机抽。
3. **推送给需要考试的人** → 每个计划配置「参考范围」（批次、板块、班级、班级职务、指定人员），发布时固化参与人名单。
4. **推送时间可设置** → 计划配置「每日推送时刻」，例如每天 00:00。
5. **完成时间可设置** → 计划配置「每日截止时刻」，例如每天 23:59:59（即当天全天可做）。
6. **日期范围可设置** → 计划配置「开始日期 ~ 结束日期」，例如 2026-12-01 ~ 2026-12-31。
7. **每人每天抽题去重** → 同一个人在同一计划内，已经做过的题不再重复抽；题库不够时按配置策略兜底。
8. **成绩与作答记录要能查** → 每日作答独立留档，管理端可按计划/日期/人员查看和导出。

---

## 四、总体设计

### 4.1 业务流程（管理端）

```
导入题目（复用题库管理）
      ↓
新建「每日练习计划」：选题库 / 每日题数 / 分值 / 日期范围 / 推送时刻 / 截止时刻 / 参考范围
      ↓
保存草稿 → 发布（固化参与人名单，校验题库可用量）
      ↓
每天到推送时刻，定时任务为每位参与人生成「当天一套题」
      ↓
学员在「每日练习」页面作答 → 提交 → 系统自动判分 → 记录留档
      ↓
管理端查看每日完成率、按人/按天查明细、导出
```

### 4.2 关键设计取舍

| 取舍点 | 选择 | 原因 |
|---|---|---|
| 复用 `exam_plan` 还是新建表 | **新建 `exam_daily_*`** | 规则差异大，避免影响已上线考试逻辑 |
| 防作弊（全屏/切屏记录） | **不做**，只保留「限时」 | 每日练习是学习性质，强防作弊会显著增加复杂度和误伤 |
| 成绩是否进入月度评价 | **默认不进** | 防止污染既有评价口径；如需要另立需求 |
| 推送渠道 | **站内待办 + 首页待办数** | 平台目前没有短信/微信/邮件通道，见第 9 节说明 |
| 抽题去重范围 | **同一计划内、同一人** | 跨计划去重会让计划之间互相影响，先不做 |
| 题库不够时 | 可配置：**循环复用 / 减少题数 / 跳过该人** | 默认循环复用，保证不断推 |
| 参与人名单 | **发布时快照 + 生成时过滤在职** | 名单稳定、可追溯；新入职可手工刷新加入 |

---

## 五、数据库设计（新增迁移 V55）

> 约定：所有时间仍按现有系统习惯存北京时间（`Asia/Shanghai`），`DATETIME` 不带时区；日期用 `DATE`。不修改任何历史迁移。

### 5.1 表清单

| 表 | 作用 | 预估量级 |
|---|---|---|
| `exam_daily_plan` | 每日练习计划（规则、时段、状态） | 几十条 |
| `exam_daily_plan_target` | 计划的参考范围（批次/板块/班级/职务/指定人） | 每计划几条到几十条 |
| `exam_daily_participant` | 计划参与人快照（可排除个别人员） | 每计划 ≤ 员工数 |
| `exam_daily_session` | 每人每天一条「当日练习」 | 186 人 × 31 天 ≈ 5,766 条/月 |
| `exam_daily_session_question` | 当日练习抽到的题目快照（含去重唯一键） | 5,766 × 10 ≈ 5.8 万条/月 |
| `exam_daily_answer` | 每道题的作答与判分 | 同题目快照量级 |
| `exam_daily_job_run` | 每天生成任务的执行日志（幂等 + 补推依据） | 每计划每天 1 条 |
| `exam_daily_plan_exclusion` | 计划排除日期（节假日等） | 少量 |

### 5.2 建表 SQL 草案（仅设计，不在本次执行）

```sql
-- 1. 每日练习计划
CREATE TABLE exam_daily_plan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(500),
  bank_ids JSON NULL COMMENT '限定题库ID，空表示全部启用题库',
  question_types JSON NULL COMMENT '限定题型，默认单选/多选/判断',
  tags JSON NULL COMMENT '限定专业标签，空表示不限；公共题按现有规则参与',
  daily_count INT NOT NULL COMMENT '每日题数',
  score_per_question DECIMAL(5,2) NOT NULL COMMENT '每题分值',
  pass_score DECIMAL(5,2) NULL COMMENT '及格分，可空',
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  push_time TIME NOT NULL DEFAULT '00:00:00' COMMENT '每日推送时刻',
  deadline_time TIME NOT NULL DEFAULT '23:59:59' COMMENT '每日截止时刻',
  timezone VARCHAR(40) NOT NULL DEFAULT 'Asia/Shanghai',
  randomize_options BOOLEAN NOT NULL DEFAULT TRUE,
  repeat_policy VARCHAR(20) NOT NULL DEFAULT 'ALLOW_REPEAT'
    COMMENT 'ALLOW_REPEAT/REDUCE_COUNT/FAIL',
  include_weekends BOOLEAN NOT NULL DEFAULT TRUE,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
    COMMENT 'DRAFT/PUBLISHED/PAUSED/ENDED',
  published_at DATETIME NULL,
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_exam_daily_plan_name UNIQUE(name),
  INDEX idx_exam_daily_plan_status(status,start_date,end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. 计划参考范围
CREATE TABLE exam_daily_plan_target (
  plan_id BIGINT NOT NULL,
  target_type VARCHAR(20) NOT NULL
    COMMENT 'BATCH/BUSINESS_UNIT/CLASS/CLASS_POSITION/EMPLOYEE',
  target_id BIGINT NOT NULL,
  PRIMARY KEY(plan_id,target_type,target_id),
  CONSTRAINT fk_edpt_plan FOREIGN KEY(plan_id) REFERENCES exam_daily_plan(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. 参与人快照
CREATE TABLE exam_daily_participant (
  plan_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  left_at DATETIME NULL,
  PRIMARY KEY(plan_id,employee_id),
  CONSTRAINT fk_edpa_plan FOREIGN KEY(plan_id) REFERENCES exam_daily_plan(id) ON DELETE CASCADE,
  CONSTRAINT fk_edpa_employee FOREIGN KEY(employee_id) REFERENCES employee(id),
  INDEX idx_edpa_employee(employee_id,active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. 每人每天一套
CREATE TABLE exam_daily_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  practice_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING/IN_PROGRESS/SUBMITTED/EXPIRED',
  question_count INT NOT NULL,
  correct_count INT NULL,
  total_score DECIMAL(5,2) NULL,
  started_at DATETIME NULL,
  deadline_at DATETIME NOT NULL,
  submitted_at DATETIME NULL,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_eds_plan_employee_date(plan_id,employee_id,practice_date),
  CONSTRAINT fk_eds_plan FOREIGN KEY(plan_id) REFERENCES exam_daily_plan(id),
  CONSTRAINT fk_eds_employee FOREIGN KEY(employee_id) REFERENCES employee(id),
  INDEX idx_eds_employee_date(employee_id,practice_date),
  INDEX idx_eds_plan_date_status(plan_id,practice_date,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. 当日题目快照 + 去重唯一键
CREATE TABLE exam_daily_session_question (
  session_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  plan_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  score DECIMAL(5,2) NOT NULL,
  sort_order INT NOT NULL,
  repeated BOOLEAN NOT NULL DEFAULT FALSE COMMENT '题库不足时是否重复抽到',
  PRIMARY KEY(session_id,question_id),
  UNIQUE KEY uk_edsq_plan_employee_question(plan_id,employee_id,question_id),
  CONSTRAINT fk_edsq_session FOREIGN KEY(session_id) REFERENCES exam_daily_session(id) ON DELETE CASCADE,
  CONSTRAINT fk_edsq_question FOREIGN KEY(question_id) REFERENCES question_bank(id),
  INDEX idx_edsq_session_sort(session_id,sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. 作答与判分
CREATE TABLE exam_daily_answer (
  session_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  answer_json JSON NULL,
  is_correct BOOLEAN NULL,
  score DECIMAL(5,2) NULL,
  saved_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(session_id,question_id),
  CONSTRAINT fk_eda_session FOREIGN KEY(session_id) REFERENCES exam_daily_session(id) ON DELETE CASCADE,
  CONSTRAINT fk_eda_question FOREIGN KEY(question_id) REFERENCES question_bank(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7. 每日生成任务日志（幂等）
CREATE TABLE exam_daily_job_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id BIGINT NOT NULL,
  practice_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
    COMMENT 'RUNNING/SUCCESS/FAILED/SKIPPED',
  generated_count INT NOT NULL DEFAULT 0,
  failed_count INT NOT NULL DEFAULT 0,
  error_message VARCHAR(500) NULL,
  started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at DATETIME NULL,
  UNIQUE KEY uk_edjr_plan_date(plan_id,practice_date),
  CONSTRAINT fk_edjr_plan FOREIGN KEY(plan_id) REFERENCES exam_daily_plan(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 8. 排除日期（节假日）
CREATE TABLE exam_daily_plan_exclusion (
  plan_id BIGINT NOT NULL,
  exclude_date DATE NOT NULL,
  reason VARCHAR(200),
  PRIMARY KEY(plan_id,exclude_date),
  CONSTRAINT fk_edpe_plan FOREIGN KEY(plan_id) REFERENCES exam_daily_plan(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 9. 抽题性能索引（现有只有 question_type+enabled 联合索引）
ALTER TABLE question_bank ADD INDEX idx_question_bank_bank_type_enabled(bank_id,question_type,enabled);
```

### 5.3 设计说明

- **`uk_edsq_plan_employee_question` 是「每人每天去重」的最终保障**：应用层先排除已做题，数据库再用唯一键兜底，即使并发或重跑也不会重复插同一个人同一道题。
- `exam_daily_session_question` 冗余 `plan_id`、`employee_id` 是为了这个唯一键和批量查询历史题；否则每次都要回表 `session`。
- `exam_daily_job_run` 的 `uk(plan_id, practice_date)` 是「不漏推/不重推」的开关：同一天只有一条记录，重跑只更新状态。
- 所有表都不动既有表结构（只给 `question_bank` 加一个索引）。

---

## 六、题库批量导入（1 万题量级）

### 6.1 现状与问题

现有导入接口 `POST /api/v1/exams/questions/import`（[ExamController.java 第 56-65 行](file:///d:/work/talent-platform/backend/src/main/java/com/talent/platform/exam/ExamController.java#L56-L65)）的做法是：

1. `EasyExcel.read(...).doReadSync()` 一次性把整个 Excel 读进内存；
2. 全量逐行校验，任一行出错就整体不导入，返回全部错误行；
3. 校验通过后**一条一条 `insert`**，包在一个事务里。

1 万题时的问题：

| 问题 | 影响 |
|---|---|
| 单条 insert × 10000 | 本机可能 5–15 秒，线上更慢 |
| 前端 axios 全局超时 **20 秒**（[api.ts 第 3 行](file:///d:/work/talent-platform/frontend/src/api.ts#L3)） | 可能后端成功、前端报超时，用户以为失败又导一次 → 重复数据 |
| 错误行全部返回 | 文件格式错时可能返回上万条错误，响应体巨大 |
| 无重复题检测 | 同一文件重复导入会翻倍 |

### 6.2 改造方案（只改导入，不动题库结构）

1. **分批写入**：用 `JdbcTemplate.batchUpdate`，每 500 条一批，10k 题通常 1–3 秒内完成。
2. **前端单独放宽超时**：导入请求传 `timeout: 180000`，不影响其他接口的 20 秒。
3. **行数上限**：服务端限制单文件 20,000 行（可配置 `app.exam.import-max-rows`），超限直接提示拆分导入。
4. **错误只返回前 100 条**：响应增加 `errorCount` 总错误数，前端提示「共 N 行有问题，以下为前 100 条」。
5. **可选查重**（默认开）：同一题库内「题型 + 题干」完全相同视为重复。导入前把目标题库已有题干读进内存（1 万条约 2–5 MB，可接受），重复行跳过并计数；响应增加 `skipped`。若用户希望「覆盖更新」则另立需求，本期只做「跳过」。
6. **校验规则完全沿用**现有 `QuestionImportRow` / `parseImportRow`：题型、选项、答案字母、分值精度、标签长度，保证与手工录题一致。
7. **事务边界**：整批仍是一个事务。只要有一行格式错误，整体不写入（保持现有「全有或全无」语义）；「重复跳过」不算错误。

### 6.3 导入后的抽题可用量校验

发布计划时按计划条件统计可用题数（启用题目 + 启用题库 + 题型 + 标签 + 题库范围）。若可用题数 < 每日题数，直接阻止发布并提示具体缺口，避免上线后才发现抽不出题。

---

## 七、抽题规则与每人每天去重

### 7.1 抽题条件（复用现有语义）

沿用现有 `eligibleQuestionIds`（[ExamController.java 第 558-582 行](file:///d:/work/talent-platform/backend/src/main/java/com/talent/platform/exam/ExamController.java#L558-L582)）的过滤口径：

- 题目 `question_bank.enabled = true`
- 所属题库 `exam_question_bank.enabled = true`
- `question_type` 在计划允许范围内（默认 `SINGLE/MULTIPLE/TRUE_FALSE`，只做客观题，保证自动判分）
- 计划指定了 `bank_ids` 时限定题库
- 计划指定了 `tags` 时，按现有规则：标签命中的题 + 无标签的公共题都参与

不同点：不再要求「试卷总分 = 100 分」，每日题数与每题分值由计划决定。

### 7.2 抽题算法（不依赖 `ORDER BY RAND()`）

每天每个计划执行一次：

1. 读取当天可用题 ID 列表（1 万条 ID 很小，可整批读入内存）。
2. 一次性查出该计划下所有参与人的历史已抽题（`select employee_id, question_id from exam_daily_session_question where plan_id=?`，按月计划约 6 万条，可控；也可按人查）。
3. 对每位参与人：
   - 候选 = 可用题 − 本人已做过的题；
   - 用**确定性随机种子**打乱：`new Random(planId * 1_000_003L + 日期.toEpochDay() * 31L + employeeId)`。
     - 好处：同一天重跑会得到同一套题，配合唯一键可以安全补齐，不会出现「重跑换题」。
   - 取前 `daily_count` 题；题序即打乱后的顺序。
4. 题库不够时按 `repeat_policy`：
   - `ALLOW_REPEAT`（默认）：从已做过的题中按「最久未出现」优先补足，标记 `repeated = true`；前端提示「今日含复习题」。
   - `REDUCE_COUNT`：能抽多少抽多少，本次少题。
   - `FAIL`：该人当天不生成，写入 `job_run.error_message`，管理端可见。
5. 选项乱序：复用现有按种子打乱选项的思路（`attemptPayload` 中 `Collections.shuffle(values, new Random(seed))`），种子用 `sessionId ^ questionId`，保证同一份答卷刷新后选项顺序不变。

### 7.3 为什么不用一条 SQL 随机

`ORDER BY RAND()` 在 1 万行上会全表排序；更要命的是「排除本人已做过的题 + 每人不同」很难用一条 SQL 表达。按计划/按人取 ID 再内存打乱，量级完全够用（186 人 × 1 万 ID 的集合运算，毫秒到百毫秒级）。

---

## 八、推送对象与范围

### 8.1 配置项

沿用现有「考试计划」的筛选方式（[ExamPlansView.vue 第 188-206 行](file:///d:/work/talent-platform/frontend/src/views/exams/ExamPlansView.vue#L188-L206)）和候选接口（[ExamController.java 第 153-167 行](file:///d:/work/talent-platform/backend/src/main/java/com/talent/platform/exam/ExamController.java#L153-L167)）：

- 批次 `batchIds`（多选）
- 板块 `businessUnitIds`（多选）
- 班级 `classId`
- 班级职务 `classPositionId`
- 关键字（姓名/工号）
- 匹配后可手工勾选/取消个别人

### 8.2 快照策略

- **发布时**把匹配结果写入 `exam_daily_participant`（只写 `employee.status='ACTIVE'` 且有 `user_id` 的人）。
- 发布后新增入职/调站的人**不会自动进入**；管理端提供「刷新参与人」按钮，按当前规则重新匹配并追加（不删除已有历史）。
- 生成每日任务时再校验一次 `employee.status='ACTIVE'`；离职/停用的人当天不生成，但历史记录保留。
- 管理端可对单个人员「停止参与」（`active=false, left_at=now()`），不停用其历史数据。

### 8.3 身份限制

沿用现有考试规则：只有**当前生效身份为 `EMPLOYEE`**、且账号绑定了员工档案的人才能作答（现有 `start()` 第 182 行就是这么校验的）。管理员/导师身份看不到作答入口，切回员工身份后才可作答。这一点与多角色改造后的「当前身份」语义一致。

---

## 九、推送时段与每日完成时限

### 9.1 配置项与计算

| 字段 | 含义 | 默认 |
|---|---|---|
| `start_date` / `end_date` | 计划生效日期区间（含首尾） | 必填 |
| `push_time` | 每天生成题目的时刻 | 00:00:00 |
| `deadline_time` | 每天作答截止时刻 | 23:59:59 |
| `include_weekends` | 周末是否推送 | true |
| `exam_daily_plan_exclusion` | 指定日期不推送（节假日） | 空 |

每人每天的作答窗口 = `practice_date + push_time` ~ `practice_date + deadline_time`（北京时间）。
校验：`push_time < deadline_time`，且计划区间 `end_date >= start_date`。

### 9.2 「推送」在本系统里的真实含义（必须写清楚）

平台目前**没有**短信、微信、邮件、App 推送通道，也没有消息中心表。本期「推送」落地为：

1. 每天到点，系统为该用户生成当天的一套题（状态 `PENDING`）；
2. 学员登录后，在「考试中心 → 每日练习」看到「今日任务」，未完成的显示醒目提醒；
3. 首页/侧边栏待办数增加（改造 `dashboard` 的 `pending_daily_practices` 指标，见第 12 节）。

如果业务上必须做到「手机弹通知」，需要另接短信/公众号/企业微信，这属于新渠道建设，不在本方案范围内，需要单独评估费用与合规。

### 9.3 时间与时区

- 全系统统一按北京时间（`Asia/Shanghai`）解释，`TimeWindow.now()` 已经是这个口径（[TimeWindow.java 第 8 行](file:///d:/work/talent-platform/backend/src/main/java/com/talent/platform/schedule/TimeWindow.java#L8)）。
- 计划表保留 `timezone` 字段但 V1 固定 `Asia/Shanghai`，界面明确提示「所有时间均为北京时间」。
- 中国无夏令时，不存在跳变；海外人员按北京时间截止，文档中说明。

---

## 十、定时任务与补偿

### 10.1 任务设计

新增 `DailyPracticeScheduler`：

```java
// 每 30 秒扫描一次，足够覆盖「00:00 推送」且不会造成压力
@Scheduled(fixedDelay = 30000, initialDelay = 20000)
public void generateDailySessions() { service.generateDueSessions(); }
```

`generateDueSessions()` 逻辑：

1. 查所有 `status='PUBLISHED'`、`start_date <= 今天 <= end_date` 的计划；
2. 过滤掉 `include_weekends=false` 的周末、`exam_daily_plan_exclusion` 中的日期；
3. 若 `现在 < 今天 + push_time`，跳过（还没到推送时刻）；
4. 若 `exam_daily_job_run(plan_id, 今天)` 已存在且 `SUCCESS`，跳过（幂等）；
5. 否则抢占式插入一条 `RUNNING` 记录（`INSERT IGNORE`，受影响行数为 0 说明别的线程/实例已在跑），再逐人生成；
6. 每人生成时用 `INSERT IGNORE` 写 `exam_daily_session`，唯一键冲突视为已生成；题目快照批量插入；
7. 完成后把 `job_run` 更新为 `SUCCESS`（记录生成数/失败数），异常则 `FAILED` + 错误摘要。

### 10.2 漏推与补推

| 场景 | 处理 |
|---|---|
| 00:00 时服务刚重启/短暂不可用 | 30 秒轮询会在服务恢复后立即补生成当天任务（只要还没过截止时间） |
| 推送时刻设成 08:00，服务 07:59–08:05 重启 | 恢复后扫描到「已到点但无成功记录」，补生成 |
| 服务停了一整天 | 当天 `job_run` 标记 `FAILED/MISSED`；次日只生成次日任务，**不自动补昨天的题**（补了也做不了） |
| 管理端发现漏推 | 提供「立即推送今天」按钮，调用同一套生成逻辑（幂等，不会重复） |
| 想让人补做昨天 | 提供「为指定人员延长今天/指定日期截止时间」的补救接口（P1，可后置） |
| 节假日 | 预先在计划里加排除日期；当天 `job_run` 记 `SKIPPED`，不生成、不计入完成率 |

**不做**「自动补生成历史日期」的原因：历史日期的截止时间已过，生成了也是 `EXPIRED` 状态，只会污染统计。管理端「补推」也只允许当天。

### 10.3 调度线程池问题（必须一并处理）

当前项目没有配置 `spring.task.scheduling.pool.size`，Spring 默认**只有 1 个调度线程**。现有任务已经不少：

- 考试超时交卷：每 5 秒
- 成绩发布：每分钟
- 请假逾期扫描：每分钟
- 任务归档：每分钟
- 附件票据清理：每小时
- 月度评价：每月 1 日

新增的「每日生成」如果较慢（186 人 × 10 题 + 批量插入，预计 1–5 秒），会把其他每分钟任务往后挤。建议在 `application.yml` 增加：

```yaml
spring:
  task:
    scheduling:
      pool:
        size: 4
```

这是全局配置变更，但只影响「任务之间是否互相等待」，不改变任何业务逻辑，风险低。若不想动全局，也可以给新任务单独建 `ThreadPoolTaskScheduler`，但代码更多。**建议采用前者**。

### 10.4 并发与多实例

- 生产目前是单台 ECS、单个 app 容器，不存在多实例并发。
- 仍按多实例安全设计：`uk_exam_daily_job_run(plan_id, practice_date)` + `INSERT IGNORE` 抢占；`uk_exam_daily_session` 防重复；题目快照唯一键防重复抽题。
- 管理端两个浏览器同时点「立即推送」也只会生成一次。

---

## 十一、成绩与作答记录归属

### 11.1 存储与判分

- 作答存 `exam_daily_answer`，题目快照存 `exam_daily_session_question`，当天汇总存 `exam_daily_session`。
- 只支持客观题（单选/多选/判断），提交即判分：
  - 复用现有判分口径 `ExamScoringService.answersMatch`（[ExamScoringService.java 第 62 行](file:///d:/work/talent-platform/backend/src/main/java/com/talent/platform/exam/ExamScoringService.java#L62)）——多选必须完全一致，少选/多选都不得分。
  - `total_score = 正确题数 × 每题分值`，`correct_count` 同步记录。
  - 若配置了 `pass_score`，前端显示「通过/未通过」。
- 状态流转：`PENDING`（已生成未开始）→ `IN_PROGRESS`（已开始）→ `SUBMITTED`（已提交并判分）；超过 `deadline_at` 未提交 → `EXPIRED`（未作答，成绩 0）。

### 11.2 与现有成绩体系的关系

- **不写入** `exam_attempt` / `exam_answer` / `score_summary`，不参与月度评价、季度评价的自动计算。
- 在「考试中心 → 成绩管理」新增一个只读页签「每日练习成绩」，按计划/日期/人员查看和导出；不改现有考试成绩列表的 SQL。
- 如果后续确认要计入评价，需要单独设计「练习成绩 → 评价分值」的映射与权重，不能直接混入，否则会改变已发布月份的口径（违反「已发布数据不可回写」原则）。

### 11.3 历史不可变

- 提交后的 `session`、`session_question`、`answer` 不允许修改。
- 修改计划（题数、题库、分值）只影响**尚未生成**的日期；已生成的历史日期不受影响。
- 计划发布后，原则上不允许改「题库范围/每日题数/每题分值」；只允许改：暂停、延长结束日期、追加参与人、调整未来日期的推送/截止时刻。若确需改规则，建议新建计划。

---

## 十二、页面与交互改动

### 12.1 管理端（新页面）

新增 `frontend/src/views/exams/DailyPracticePlansView.vue`，路由 `/exams/daily-plans`，权限 `exam:manage`。

页面结构参照现有「考试计划」页（复用 `exam-center.css` 的卡片、表格、抽屉样式）：

1. **顶部概览卡**：计划总数、进行中、今日生成成功/失败、今日完成率。
2. **计划列表**：名称、题库、每日题数、日期范围、推送/截止时刻、参与人数、今日生成状态、状态、操作。
3. **新建/编辑抽屉**（分 3 段）：
   - 基本信息：名称、说明、题库多选、题型多选、专业标签；
   - 练习规则：每日题数、每题分值、及格分、选项乱序、题库不足策略、是否含周末；
   - 时间与范围：开始/结束日期、每日推送时刻、每日截止时刻、批次/板块/班级/职务/关键字 → 匹配人员 → 勾选。
4. **发布前校验**：可用题数 ≥ 每日题数、日期区间合法、推送时刻 < 截止时刻、至少 1 名参与人。
5. **今日任务面板**：每个计划的生成状态（成功/失败/跳过）、失败原因、「立即推送今天」按钮。
6. **参与人管理**：查看、停止参与、刷新参与人。
7. **成绩页签**：按日期/人员筛选，查看得分与明细，导出 Excel。

> 说明：不新建 CSS 文件，尽量复用 `styles/exam-center.css`；图表类统计本期先用数字卡片，不引入新依赖。

### 12.2 学员端（新页面）

新增 `frontend/src/views/exams/DailyPracticeView.vue`，路由 `/exams/daily`，所有登录用户可进；无员工档案或非员工身份时显示空态提示。

页面内容：

1. **今日任务卡**：今日题数、剩余时间倒计时（服务端时间校正，复用 `examProctor.ts` 里的 `serverClockOffsetMillis` / `formatExamCountdown`）、开始按钮。
2. **作答区**：单选/多选/判断，样式复用现有答题组件写法；不做全屏、不记录切屏；答案自动保存（`on duplicate key update`）。
3. **提交**：二次确认；倒计时归零自动提交（前端 + 后端双重兜底：`attemptStatus` 定时同步 + 后端定时任务扫描过期）。
4. **结果**：提交后立即显示得分、正确率、逐题对错与解析（练习性质，允许即时看答案）。
5. **历史记录**：按日期列出过去每天的状态与得分。

**为什么不在 `MyExamsView.vue` 里加页签**：那个文件里有 200 多行防作弊/全屏/异常离场的复杂逻辑，动它回归风险高。新页面独立，互不影响；入口在菜单和首页待办里。

### 12.3 菜单与首页

- `frontend/src/layout/AppLayout.vue`（第 27-38 行 `examChildren`）：
  - 管理身份增加「每日练习计划」→ `/exams/daily-plans`
  - 所有身份增加「每日练习」→ `/exams/daily`
- `DashboardController` 员工首页指标增加 `pending_daily_practices`（今日未完成的练习数），并在 `action_items` 里增加一条「今日练习待完成」。
- `frontend/src/utils/roleTodo.ts` 的 `todoCount()` 把 `pending_daily_practices` 计入员工待办数（否则侧边栏红点不含每日练习）。

### 12.4 题库页改动

`ExamQuestionBankView.vue` 只改导入相关：

- 导入请求加 `timeout: 180000`；
- 成功提示显示「成功导入 N 道，跳过重复 M 道」；
- 失败提示显示「共 N 行有问题，前 100 条如下」；
- 其余列表、编辑、启停逻辑不动。

---

## 十三、接口清单

统一前缀 `/api/v1/exam-daily`，沿用现有 `ApiResponse` 包装、JWT 鉴权、`PermissionService` 校验、`AuditService` 审计。

### 13.1 管理端（`exam:manage`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/plans` | 计划列表（含今日生成状态、参与人数、今日完成率） |
| POST | `/plans` | 新建计划（草稿） |
| GET | `/plans/{id}` | 计划详情（规则、范围、参与人、排除日期） |
| PUT | `/plans/{id}` | 编辑计划（`version` 乐观锁；已发布计划限制可改字段） |
| DELETE | `/plans/{id}` | 删除草稿（仅 `DRAFT`） |
| POST | `/plans/{id}/publish` | 发布：校验题量、固化参与人 |
| POST | `/plans/{id}/pause` | 暂停（不再生成新的一天） |
| POST | `/plans/{id}/resume` | 恢复 |
| GET | `/candidates` | 按范围匹配候选人员（复用现有候选人 SQL 口径） |
| POST | `/plans/{id}/participants/refresh` | 按当前范围刷新参与人（只追加，不删历史） |
| PUT | `/plans/{id}/participants/{employeeId}` | 停止/恢复单个人员参与 |
| POST | `/plans/{id}/push` | 立即推送今天（幂等，用于补推） |
| GET | `/plans/{id}/runs` | 每日生成日志（分页/按日期） |
| GET | `/plans/{id}/results` | 成绩列表（按日期/人员筛选） |
| GET | `/plans/{id}/results/export` | 导出 Excel |
| POST | `/plans/{id}/exclusions` | 增加排除日期 |
| DELETE | `/plans/{id}/exclusions/{date}` | 删除排除日期 |

### 13.2 学员端

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/my` | 今日任务 + 历史列表（只返回本人） |
| POST | `/sessions/{id}/start` | 开始/继续今日练习（校验身份、时间窗、状态） |
| GET | `/sessions/{id}` | 取题（未提交不返回答案与解析） |
| PUT | `/sessions/{id}/answers` | 保存单题答案（自动保存，幂等） |
| POST | `/sessions/{id}/submit` | 提交并判分（幂等，重复提交返回已有结果） |
| GET | `/sessions/{id}/result` | 提交后查看逐题结果与解析 |

### 13.3 返回字段约定

- 时间统一返回 `yyyy-MM-ddTHH:mm:ss`（与现有接口一致，前端用 `dateTimeParts` 处理）。
- 倒计时返回 `server_now_epoch_ms` 和 `deadline_epoch_ms`，前端按服务器时间校正，避免客户端改时间作弊（与现有考试一致）。

---

## 十四、边界情况与风险

### 14.1 业务边界

| 场景 | 处理 |
|---|---|
| 推送时刻已过但服务刚启动 | 30 秒轮询补生成当天任务 |
| 服务宕机一整天 | 当天记 `FAILED/MISSED`，不补历史；次日正常 |
| 管理端手工补推 | 只允许当天，幂等 |
| 节假日/周末 | 排除日期表 / `include_weekends`，跳过并记 `SKIPPED` |
| 题库不足 | 按策略循环复用/减题/跳过，并给管理端告警 |
| 员工离职/停用 | 停止生成新任务，历史保留 |
| 计划中途新增人员 | 管理端「刷新参与人」追加；从加入当天开始推送 |
| 学员 23:59 提交 | 服务端以 `deadline_at` 为准，超过即 `EXPIRED`，前端倒计时只是辅助 |
| 重复点击提交 | 后端 `where status='IN_PROGRESS'` 守卫 + 幂等返回 |
| 题目被停用/删除 | 已生成的快照仍可作答；删除被外键拦住，需先停用 |
| 题库/题目被修改 | 已生成的快照不回溯（题目内容仍读 `question_bank` 当前值，需注意） |
| 多角色用户 | 只有当前身份 `EMPLOYEE` 可作答 |
| 计划发布时已过今日推送时刻 | 建议：若未过截止时间，发布后立即生成今天；否则从下一个推送日生效（待确认） |

> **一个必须注意的细节**：`exam_daily_session_question` 只存 `question_id`，题干/选项仍从 `question_bank` 实时读。如果管理员事后编辑了题目内容，历史答卷看到的就是新内容，与当时作答可能不一致。现有考试也是同样机制（`exam_answer` 只存答案）。若要严格冻结题干，需要在快照表里冗余 `stem/options_json/answer_json`，代价是存储翻倍。**建议 V1 不做**，但在计划发布后限制编辑已被抽到的题目（提示"该题已被每日练习使用"），与现有 `deleteQuestion` 的保护思路一致。此点需确认。

### 14.2 技术风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 1 万题同步导入超时 | 前端报错但数据已入库，重复导入 | 批量写入 + 前端 180s 超时 + 行数上限 |
| 调度线程只有 1 个 | 每日生成拖慢 5 秒超时交卷任务 | `spring.task.scheduling.pool.size: 4` |
| 抽题在内存做集合运算 | 1 万题 × 186 人，单次几百毫秒 | 每天只算一次，结果落库；不在请求线程做 |
| `session_question` 增长 | 每年约 68 万行 | 联合索引 + 按年归档策略（后续） |
| 生成任务中途异常 | 部分人生成、部分失败 | `job_run` 记录 + 幂等重跑补齐（唯一键） |
| 计划编辑与生成并发 | 生成用了旧规则 | 生成前读计划加 `version` 快照；管理端编辑有乐观锁 |
| 删除题目报 500 | 用户体验差 | 扩展 `deleteQuestion` 的占用检查，加入 `exam_daily_session_question`，返回友好提示 |
| 与现有考试共用题库 | 题库被停用影响两边 | 停用题库前提示「有 N 个进行中的每日练习使用该题库」 |

### 14.3 明确不做（避免范围蔓延）

- 不做短信/微信/邮件推送通道；
- 不做主观题（简答）与人工阅卷；
- 不做防作弊（全屏、切屏记录、异常离场倒计时）；
- 不做练习成绩进入月度/季度评价；
- 不做跨计划去重；
- 不做「错题本」「智能推荐」等衍生功能。

---

## 十五、预计改动文件清单

### 15.1 后端新增

| 文件 | 作用 |
|---|---|
| `backend/src/main/resources/db/migration/V55__exam_daily_practice.sql` | 8 张新表 + 1 个索引 |
| `backend/src/main/java/com/talent/platform/exam/DailyPracticeController.java` | 管理端 + 学员端接口 |
| `backend/src/main/java/com/talent/platform/exam/DailyPracticeService.java` | 计划 CRUD、发布、参与人、抽题、判分、生成 |
| `backend/src/main/java/com/talent/platform/exam/DailyPracticeScheduler.java` | 30 秒轮询生成 + 过期扫描 |
| `backend/src/main/java/com/talent/platform/exam/DailyPracticeExportRow.java` | 成绩导出 Excel 行模型 |
| `backend/src/test/java/com/talent/platform/exam/DailyPracticeServiceTest.java` | 抽题去重、题库不足、幂等、时限等单测 |

> 放在 `com.talent.platform.exam` 包下（而不是子包），是为了直接复用包内可见的 `ExamScoringService.answersMatch`、`ExamController` 的私有工具思路（复制少量工具方法，不改原文件可见性）。

### 15.2 后端修改

| 文件 | 改动 |
|---|---|
| `ExamController.java` | ① 导入改批量写入、错误上限、可选查重；② `deleteQuestion` 的占用检查增加 `exam_daily_session_question`；③ 题库停用时增加每日练习占用提示（只读查询，现有接口没有删除题库的能力） |
| `DashboardController.java` | 员工指标增加 `pending_daily_practices`，待办列表增加一条练习提醒 |
| `application.yml` | 增加 `spring.task.scheduling.pool.size: 4`（见 10.3） |

**不改**：`exam_plan` / `exam_paper` / `exam_attempt` / `exam_answer` 相关读写、现有 `ExamScheduler`、现有考试页面逻辑、权限常量。

### 15.3 前端新增

| 文件 | 作用 |
|---|---|
| `frontend/src/views/exams/DailyPracticePlansView.vue` | 管理端计划配置与今日生成面板 |
| `frontend/src/views/exams/DailyPracticeView.vue` | 学员端今日练习 + 历史 |
| `frontend/src/views/exams/dailyPracticeUi.ts` | 状态文案、倒计时、题型标签等小工具（可选） |

### 15.4 前端修改

| 文件 | 改动 |
|---|---|
| `frontend/src/router.ts` | 新增 `/exams/daily-plans`（`exam:manage`）与 `/exams/daily`（登录即可） |
| `frontend/src/layout/AppLayout.vue` | `examChildren` 增加两个菜单项 |
| `frontend/src/utils/roleTodo.ts` | `todoCount()` 计入 `pending_daily_practices` |
| `frontend/src/views/exams/ExamQuestionBankView.vue` | 导入超时、重复跳过提示、错误条数提示 |

### 15.5 文档

| 文件 | 改动 |
|---|---|
| `docs/api-contract.md` | 增加每日练习接口章节 |
| `outputs/考试每日推送-设计方案.md` | 本文件 |

---

## 十六、实施阶段与验收

### 阶段一：地基（可独立验收）
- V55 迁移在本地 MySQL 8.4 执行成功，Flyway 到 V55、失败 0；
- 题库导入 1 万题成功，耗时记录在案，重复导入被跳过；
- 现有后端测试全部通过（不新增失败）。

### 阶段二：管理端配置
- 新建计划 → 发布 → 参与人快照正确；
- 题量不足时发布被阻止，提示具体缺口；
- 暂停后不再生成，恢复后继续；
- 排除日期生效。

### 阶段三：每日生成与作答
- 手动触发（或改系统时间模拟）在推送时刻生成当天任务；
- 同一天重复触发不产生重复 session；
- 每人每天题目不重复（跨天验证 3 天以上）；
- 题库不足时三种策略行为符合配置；
- 提交判分正确，多选少选不得分；
- 23:59:59 后无法提交，状态为 `EXPIRED`。

### 阶段四：补推、统计与收尾
- 停掉服务跨过推送时刻，恢复后自动补生成；
- 手动「立即推送今天」可补齐缺失人员；
- 首页待办数与实际未完成数一致；
- 成绩页筛选、导出正确；
- 多角色切换后入口与权限正确。

### 验收口径
- 所有验证区分「亲自验证 / 日志或截图证据 / 未验证」，不把未验证项算作通过（沿用项目既有发布记录规范）。

---

## 十七、需要用户拍板的决策

| # | 决策点 | 建议 |
|---|---|---|
| 1 | 独立子模块，还是硬套现有 `exam_plan` | **独立子模块**（理由见第一节） |
| 2 | 每日练习是否计入月度/季度评价 | **不计入**，先独立留档 |
| 3 | 每日题数与分值默认值 | 10 题 × 10 分 = 100 分，及格 60 分（可改） |
| 4 | 题库不足时的默认策略 | **循环复用**（标记复习题），保证不断推 |
| 5 | 计划发布时已过当天推送时刻，是否立即生成今天 | 建议**立即生成**（只要未过截止） |
| 6 | 是否允许发布后修改题库/题数/分值 | 建议**不允许**，要改就新建计划 |
| 7 | 是否需要短信/微信提醒 | 本期**不做**，只做站内待办 |
| 8 | 历史题干是否冻结快照 | V1 **不冻结**（与现有考试一致），靠「已用题目禁止编辑」保护 |

---

## 附：与现有代码的对应关系（便于开发时定位）

| 现有能力 | 位置 | 复用方式 |
|---|---|---|
| 题库列表/新建/编辑 | [ExamController.java 第 42-50 行](file:///d:/work/talent-platform/backend/src/main/java/com/talent/platform/exam/ExamController.java#L42-L50) | 直接复用，不改 |
| 题目增删改与启停 | 同上第 51-55 行 | 直接复用，仅扩展删除占用检查 |
| Excel 导入与校验 | 同上第 56-65、650-686 行 | 复用解析逻辑，改写入方式 |
| 抽题条件过滤 | 同上第 558-582 行 | 复用过滤口径 |
| 选项乱序 | 同上第 479-496 行 | 复用种子打乱思路 |
| 客观题判分 | [ExamScoringService.java 第 62 行](file:///d:/work/talent-platform/backend/src/main/java/com/talent/platform/exam/ExamScoringService.java#L62) | 复用判分口径 |
| 考试时间窗与时区 | [TimeWindow.java](file:///d:/work/talent-platform/backend/src/main/java/com/talent/platform/schedule/TimeWindow.java) | 复用 `now()` 与北京时间口径 |
| 候选人匹配 | [ExamController.java 第 153-167 行](file:///d:/work/talent-platform/backend/src/main/java/com/talent/platform/exam/ExamController.java#L153-L167) | 复用 SQL 口径（新接口返回同样字段） |
| 定时任务写法 | [ExamScheduler.java](file:///d:/work/talent-platform/backend/src/main/java/com/talent/platform/exam/ExamScheduler.java) | 照此新增独立 Scheduler |
| 前端页面样式 | [exam-center.css](file:///d:/work/talent-platform/frontend/src/styles/exam-center.css) | 直接复用 |
| 前端倒计时校时 | [examProctor.ts](file:///d:/work/talent-platform/frontend/src/views/exams/examProctor.ts) | 复用校时与格式化函数 |
