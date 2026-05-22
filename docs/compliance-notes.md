# AI Insight 合规说明

## 1. 合规目标

AI Insight 的合规目标是让竞品分析过程在使用公开资料、用户补充资料和 LLM 能力时保持可解释、可追踪、可降级，避免泄漏敏感信息或把不可核验内容伪装成事实。

核心原则：

- 不提交真实 API Key。
- 公开网页抓取前检查 URL 和 robots。
- 用户敏感资料显式标记 internal-only。
- 证据来源保留 URL、摘要和 complianceNote。
- 证据不足时在报告中标记“待验证”。
- LLM 不可用时使用 deterministic fallback，保证演示可重复。

## 2. API Key 管理

当前配置方式：

- 小米 LLM 使用 OpenAI-compatible 配置。
- 本地通过环境变量或 `.env` 设置。
- `.env` 已加入 `.gitignore`。
- README 中只使用 `your-api-key` 示例。

禁止事项：

- 不要把真实 API Key 写入 README、docs、测试、截图或提交信息。
- 不要在答辩材料中展示 `.env` 内容。
- 不要把终端中包含 key 的日志截图放入材料。

推荐答辩话术：

```text
系统支持外部 LLM，但业务代码只依赖 LlmClient 门面；密钥通过本地环境变量注入，不进入仓库。未配置密钥时，系统使用 deterministic fallback 保证演示可重复。
```

## 3. 公开网页抓取策略

实现位置：

- `WebPageFetchService`
- `SourceCollectionService`

行为：

1. 只允许 http/https 绝对 URL。
2. 抓取前尝试读取站点 `robots.txt`。
3. robots disallow 时返回 blocked，不进入证据链。
4. robots 不可用时，MVP 阶段视为可公开抓取，但在 complianceNote 中记录。
5. 页面抓取失败时不中断流程。
6. 用户未提供 URL 时，系统使用内置公开来源 catalog。
7. 未知竞品使用 `seed-evidence://`，明确标记为演示种子证据。

EvidenceSource 会记录：

- `url`
- `sourceType`
- `snippet`
- `rawText`
- `complianceNote`

## 4. 内置公开来源 Catalog

当前覆盖：

- Notion。
- 飞书文档。
- Confluence。
- Airtable。
- 语雀。
- 腾讯文档。

用途：

- 避免无 URL 时使用虚假的 `example.com`。
- 给演示常见竞品提供真实公开入口。
- 保持流程可跑，不因网络或用户未填资料而中断。

限制：

- catalog entry 不等于实时抓取结果。
- 答辩前应核查关键公开页面是否仍然可访问。
- 最终报告中如使用 catalog snippet，应保留“需以页面原文为准”的谨慎表述。

## 5. 用户补充资料

模型：

- `UserProvidedEvidence`

字段：

- `title`
- `sourceType`
- `content`
- `url`
- `sensitive`
- `createdAt`

敏感资料处理：

- 用户勾选 sensitive 后，转成 EvidenceSource 时会写入 internal-only complianceNote。
- URL 为空时，使用 `user-evidence://{id}`。
- 报告中不应对外扩散内部访谈全文，只能作为本次 run 的分析证据。

推荐答辩话术：

```text
系统允许用户补充访谈、问卷或内部摘要，但敏感资料会被标记为 internal-only。它们进入证据链用于本次分析，不作为公开来源传播。
```

## 6. 证据和结论约束

报告约束：

- 关键结论必须带 `[S1]` 这类 citation。
- 证据不足时使用“待验证”或“证据不足”。
- 不编造价格、营收、客户案例或市场份额。

Reviewer 检查：

- `CitationCoverageEvaluator` 检查无引用结论段落。
- `ReviewerNode` 生成 ReviewFinding 和 ReviewDecision。
- 缺证据时可打回 Researcher 补采。

可观测记录：

- ReviewFinding 记录 category、message、recommendation。
- 定位字段记录 artifactId、claimId、paragraphIndex、excerpt。

## 7. LLM 输出控制

当前策略：

- Agent prompt 明确要求只基于证据和结构化产物输出。
- Writer 要求关键结论带 citation。
- Reviewer 要求检查引用覆盖和过度推断。
- 没有 LLM 时使用 deterministic fallback。

风险：

- LLM 可能产生证据外推断。
- LLM 可能漏标 citation。
- LLM 可能输出不可验证的商业判断。

缓解：

- CitationCoverageEvaluator 规则兜底。
- ReviewDecision 打回采集或修订。
- Trace 保存 prompt、输入、输出，便于复盘。

## 8. 日志和产物管理

不要提交：

- `.env`
- `frontend/dist`
- `node_modules`
- `logs`
- 包含真实 key 或敏感资料的截图、导出文件。

当前建议：

- 演示前清理临时构建产物。
- 使用示例 API Key 占位符。
- 对敏感访谈内容使用脱敏摘要。

## 9. 当前合规边界

已具备：

- API Key 不入库。
- robots 检查。
- source complianceNote。
- sensitive 用户资料标记。
- citation coverage 检查。
- fallback 可重复演示。

仍可增强：

- 引入更完整的 robots parser。
- 对抓取页面做域名 allowlist / denylist。
- 对用户资料做自动脱敏。
- 对最终报告做敏感词和 PII 检查。
- 对公开来源保留抓取时间和页面快照 hash。

## 10. 答辩风险提醒

答辩时不要承诺：

- 系统已经覆盖所有互联网公开信息。
- 内置 catalog 是实时搜索结果。
- LLM 输出完全不会出错。
- robots 检查已经达到生产级爬虫合规能力。

建议表述：

```text
当前版本是面向课题演示的可运行原型，已经把公开来源、用户资料、证据 citation、Reviewer 复核和 Trace 串成闭环。真实生产环境还会继续增强搜索、robots 策略、PII 脱敏和审计留痕。
```
