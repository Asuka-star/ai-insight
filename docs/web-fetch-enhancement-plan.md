# 网页抓取增强开发文档

## 1. 文档目的

本文记录 AI Insight 网页抓取链路的现状、问题、增强目标和分阶段开发方案。

当前系统已经接入用户公开 URL 抓取和 Tavily 搜索，能够把可用网页沉淀为 `EvidenceSource` 与 `EvidenceChunk`，并支撑后续 Extractor、Analyst、Writer、Reviewer 的 citation 链路。下一阶段的重点不是“能不能抓”，而是提升为“抓得准、来源可分级、失败可解释、证据可复用、合规可审计”。

## 2. 当前现状

### 2.1 当前链路

```text
用户输入 sourceUrls / Tavily 搜索结果
-> SourceCollectionService 去重与分配 citationKey
-> WebPageFetchService 抓取网页
-> 提取 title 和正文文本
-> EvidenceSource 保存 rawText、snippet、status、freshness、complianceNote
-> EvidenceChunkService 切片
-> 下游 Agent 使用 citationKey 引用证据
```

### 2.2 已具备能力

- 支持 http/https URL 抓取。
- 支持 `robots.txt` 基础检查。
- 支持跳转跟随。
- 支持超时控制。
- 支持抓取失败、robots 阻止和可用正文三类状态。
- 用户主动提供的 URL 即使抓取失败，也会生成失败 EvidenceSource，便于前端和 Reviewer 解释。
- 搜索结果只有在抓取到可用正文后才进入证据链，避免 Tavily 摘要直接被误当作强证据。
- 已有简单反爬页/薄内容识别，例如 Cloudflare、403、JS challenge、正文过短。

### 2.3 主要问题

1. 正文抽取仍然偏粗糙
   当前主要通过正则删除 `script/style/noscript` 和 HTML 标签，导航、页脚、cookie banner、推荐链接等噪声可能进入证据。

2. 来源类型不够细
   目前更多依赖调用方传入 `sourceType`，缺少自动识别 `official_site`、`docs`、`pricing_page`、`public_review`、`release_notes` 等类型。

3. 来源质量没有结构化表达
   Reviewer 只能间接通过 `sourceType` 和 `complianceNote` 判断证据强弱，缺少 `sourceQuality`、`failureReason` 等字段。

4. robots 和限速能力较弱
   当前每次抓取都会尝试拉取 robots，缺少 host 级缓存和限速；也没有完整处理 `Allow`、多个 user-agent group 等 robots 细节。

5. 失败原因不够可统计
   失败被写入 `status` 与 `complianceNote`，但缺少统一枚举，后续难以做指标面板和质量统计。

6. 缓存与复用不足
   同一 URL 在重跑、补采、Reviewer 打回后可能重复抓取，缺少 URL 内容缓存、hash 和复用策略。

## 3. 增强目标

### 3.1 产品目标

- 用户和评委能看懂每条证据来自哪里、质量如何、是否抓取成功。
- 报告引用不只“有 citation”，还要能解释 citation 是否可靠。
- Reviewer 可以基于来源质量提出更准确的补采建议。
- 重跑报告时尽量复用已抓取网页，减少等待和外部依赖。

### 3.2 工程目标

- 网页抓取模块具备清晰分层：获取、解析、质量评估、脱敏、安全清洗、落库。
- 抓取失败原因结构化，便于日志、前端展示和自动测试。
- 保持现有 `EvidenceSource` / `EvidenceChunk` / citationKey 链路兼容。
- 增强应支持渐进落地，不要求一次性重构所有采集逻辑。

## 4. 推荐架构

### 4.1 模块拆分

建议将现有 `WebPageFetchService` 拆成几个职责更清晰的组件：

```text
WebPageFetchService
  负责 URL 校验、调用 robots / rate limit / http fetch / parser / evaluator

RobotsPolicyService
  负责 robots.txt 获取、缓存、Allow/Disallow 判断

HostRateLimiter
  负责 host 级 QPS 控制

HtmlContentExtractor
  负责正文抽取、标题抽取、meta 抽取、正文清洗

PageQualityEvaluator
  负责判断反爬页、登录墙、薄内容、跳转页、非 HTML 等

SourceTypeClassifier
  负责根据 URL、host、path、title 推断 sourceType

FetchedPageCache
  可选，负责 URL 抓取结果缓存与复用
```

### 4.2 推荐抓取流程

```text
normalize URL
-> validate scheme / host
-> check URL cache
-> robots check with cache
-> host rate limit
-> http fetch with retry
-> content-type check
-> extract main text
-> classify source type
-> evaluate page quality
-> build FetchedPage
-> SourceCollectionService 转成 EvidenceSource
```

## 5. 数据模型建议

### 5.1 FetchedPage 增强字段

现有 `FetchedPage` 可以逐步扩展为：

```java
class FetchedPage {
    String url;
    String finalUrl;
    String title;
    String rawText;
    String snippet;
    String contentType;
    String sourceType;
    String sourceQuality;
    String status;
    String failureReason;
    String complianceNote;
    boolean usable;
    boolean robotsChecked;
    boolean redirected;
    int statusCode;
    int rawTextChars;
    String contentHash;
}
```

### 5.2 sourceType 建议枚举

```text
official_site
docs
pricing_page
release_notes
public_review
forum
article
industry_report
search_result_snippet
user_source_url
user_note
user_interview
user_survey
offline_snapshot
```

### 5.3 sourceQuality 建议枚举

```text
HIGH
MEDIUM
LOW
UNUSABLE
INTERNAL_ONLY
```

建议规则：

- `HIGH`：官网、官方文档、价格页、官方更新日志。
- `MEDIUM`：权威媒体、行业报告、产品测评文章。
- `LOW`：论坛、社区评论、普通博客、公开评价。
- `UNUSABLE`：抓取失败、robots 阻止、反爬页、正文过短。
- `INTERNAL_ONLY`：用户上传的内部访谈、问卷、敏感资料。

### 5.4 failureReason 建议枚举

```text
NONE
INVALID_URL
ROBOTS_BLOCKED
TIMEOUT
HTTP_4XX
HTTP_5XX
NON_HTML
EMPTY_TEXT
THIN_TEXT
ANTI_BOT_PAGE
LOGIN_REQUIRED
COOKIE_WALL
PARSE_FAILED
UNKNOWN
```

## 6. 分阶段开发计划

### P0：正文抽取与失败原因结构化

目标：最快提升证据质量。

任务：

- 引入 `jsoup`，替换正则式 HTML 清洗。
- 删除 `script/style/noscript/nav/header/footer/aside/svg/form` 等噪声节点。
- 优先抽取 `main`、`article`、`[role=main]`、`#content` 等正文区域。
- 保留 fallback：如果正文区域为空，再退回全页文本。
- 增加 `failureReason`，将现有失败场景映射成枚举。
- 将 `rawTextChars`、`statusCode`、`contentType` 写入 complianceNote 或 metadata。

验收：

- 官网、文档页、价格页抓取正文不再包含大量导航菜单。
- Cloudflare/JS challenge 页面不会进入搜索证据链。
- 单测覆盖 `EMPTY_TEXT`、`THIN_TEXT`、`ANTI_BOT_PAGE`、`HTTP_4XX`。

### P1：来源类型与质量分级

目标：让 Reviewer 和前端能解释证据强弱。

任务：

- 新增 `SourceTypeClassifier`。
- 基于 URL path 和 host 推断 sourceType：
  - `/pricing`、`/plans` -> `pricing_page`
  - `/docs`、`/help`、`/reference` -> `docs`
  - `/blog`、`/news` -> `article`
  - `forum`、`community`、`reddit`、`review` -> `public_review` 或 `forum`
- 新增 `sourceQuality` 规则。
- Reviewer 优先提示低质量来源支撑的高置信结论。

验收：

- 价格页、文档页、社区页能被自动分类。
- 低质量来源不应被当作强证据。
- 前端 EvidencePanel 能展示来源类型和质量。

### P2：robots 缓存、限速与重试

目标：增强稳定性和合规性。

任务：

- 新增 `RobotsPolicyService`，按 scheme + host 缓存 robots 结果。
- 支持 `Allow` 与多 user-agent group 的基本匹配。
- 新增 host 级限速，默认每 host 1 QPS。
- 5xx、timeout 支持 1-2 次重试，指数退避。
- 日志记录 host、statusCode、failureReason、retryCount。

验收：

- 同一 host 多个 URL 不重复拉 robots。
- 连续抓同一域名时有最小间隔。
- 5xx 或 timeout 可以重试，最终失败原因可见。

### P3：抓取缓存与复用

目标：支撑报告迭代和重跑。

任务：

- 新增 URL 级抓取缓存表或复用现有明细表。
- 保存 normalizedUrl、finalUrl、contentHash、rawText、title、status、fetchedAt。
- 同一 run 重跑优先复用近期缓存。
- 可选支持 `ETag`、`Last-Modified`。

验收：

- Reviewer 打回重跑时，同一 URL 不重复抓取。
- 缓存命中信息能写入 complianceNote 或 trace。
- 可以通过配置关闭缓存，便于调试。

### P4：更多内容类型

目标：覆盖竞品分析常见资料。

任务：

- 支持 `text/plain`、`text/markdown`。
- 支持 PDF 提取文本，优先用于白皮书、报告、产品手册。
- 支持 GitHub README、release notes 的更友好解析。

验收：

- PDF 可以形成 EvidenceSource 和 EvidenceChunk。
- 非 HTML 类型有明确 sourceType 和 contentType。

### P5：安全清洗与本地 RAG 统一

目标：为用户上传资料和本地 RAG 做准备。

任务：

- 对网页和用户资料统一执行 prompt injection 清洗。
- 对邮箱、手机号、access token、cookie 等敏感字段执行脱敏。
- `user_interview`、`user_survey`、`uploaded_file` 标记为内部证据。
- Researcher 支持 `local_rag_search` 作为与 Tavily 并列的信息来源。

验收：

- 用户资料不会被当作公开网页重新分发。
- Writer 生成报告时能识别内部资料引用。
- Reviewer 能提示内部证据的使用风险。

## 7. 接口与前端影响

### 7.1 后端 API

短期不需要新增 API，可以继续通过现有 run 查询返回 EvidenceSource。

中期建议让 EvidenceSource 暴露更多字段：

```text
sourceType
sourceQuality
collectionStatus
failureReason
freshness
complianceNote
retrievedAt
```

### 7.2 前端展示

EvidencePanel 建议展示：

- 来源类型。
- 来源质量。
- 抓取状态。
- 失败原因。
- 是否 robots 阻止。
- 是否内部资料。
- 原 URL / 最终 URL。

Reviewer 面板建议增加：

- 低质量来源支持的结论数量。
- 搜索证据中被丢弃的 URL 数量。
- 需要用户补充 URL / 访谈 / 问卷的缺口。

## 8. 测试计划

### 8.1 单元测试

- URL 校验：非 http/https、缺 host、非法 URI。
- robots 判断：Allow、Disallow、缺失 robots、robots 读取失败。
- 正文抽取：普通 HTML、导航噪声、空 HTML、只有脚本页面。
- sourceType 分类：pricing/docs/review/article。
- PageQualityEvaluator：反爬页、登录墙、正文过短、正常正文。

### 8.2 集成测试

- 用户 URL 抓取成功后生成 `user_source_url` EvidenceSource。
- 用户 URL 抓取失败后仍生成失败 EvidenceSource。
- Tavily 搜索结果抓取失败时不进入强证据链。
- Tavily 搜索结果抓取成功后生成 `search_result_web_page` EvidenceSource。
- 重跑时 citationKey 继续 append，不改变旧 citation 指向。

### 8.3 回归测试

- `mvn test`
- 前端 EvidencePanel 与 ReviewPanel 的展示不受字段新增影响。
- 未配置 `TAVILY_API_KEY` 时仍能通过用户 URL 和用户资料运行。

## 9. 风险与取舍

- 正文抽取库会增加依赖，需要控制包体和许可证风险。
- robots 精准实现复杂，第一期可以采用缓存 + 基础 Allow/Disallow，后续再完善。
- PDF 支持收益高但实现成本较大，不建议放在第一期。
- 过度过滤可能丢失有价值的社区评论，需要保留 LOW 质量来源，而不是一律丢弃。
- 缓存会带来 freshness 问题，需要在报告中展示抓取时间。

## 10. 建议优先级

| 优先级 | 事项 | 原因 |
| --- | --- | --- |
| P0 | jsoup 正文抽取 + failureReason | 直接提升证据质量，改动可控 |
| P1 | sourceType/sourceQuality | 支撑 Reviewer 与前端可信解释 |
| P2 | robots 缓存 + host 限速 + 重试 | 提升稳定性和合规性 |
| P3 | 抓取缓存 | 支撑历史会话和报告迭代 |
| P4 | PDF / Markdown | 扩展资料覆盖面 |
| P5 | 脱敏、安全清洗、本地 RAG | 为用户资料包和长期知识库做准备 |
