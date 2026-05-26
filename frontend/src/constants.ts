import type { AgentName, ArtifactType } from "./types";

export const AGENTS: AgentName[] = [
  "CLARIFIER",
  "RESEARCHER",
  "EXTRACTOR",
  "ANALYST",
  "WRITER",
  "REVIEWER",
  "REVISION"
];

export const AGENT_LABELS: Record<AgentName, string> = {
  CLARIFIER: "澄清范围",
  RESEARCHER: "资料采集",
  EXTRACTOR: "结构化抽取",
  ANALYST: "竞品分析",
  WRITER: "报告撰写",
  REVIEWER: "事实质检",
  REVISION: "报告修订"
};

export const AGENT_STAGE: Record<AgentName, string> = {
  CLARIFIER: "scope",
  RESEARCHER: "research",
  EXTRACTOR: "schema",
  ANALYST: "analysis",
  WRITER: "report",
  REVIEWER: "review",
  REVISION: "final"
};

export const ARTIFACT_LABELS: Record<ArtifactType, string> = {
  CLARIFICATION_BRIEF: "范围确认",
  SOURCE_LIST: "资料清单",
  RESEARCH_PLAN: "调研计划",
  COMPETITOR_PROFILE: "竞品 Schema",
  COMPETITIVE_MATRIX: "横向矩阵",
  SWOT_ANALYSIS: "SWOT 分析",
  REPORT_DRAFT: "报告草稿",
  REVIEW_FINDINGS: "复核结果",
  REPORT_REVISION: "修订说明",
  FINAL_REPORT: "最终报告"
};

export const SOURCE_OPTIONS = [
  { label: "官网", value: "official_site" },
  { label: "价格页", value: "pricing_page" },
  { label: "产品文档", value: "product_docs" },
  { label: "更新日志", value: "release_notes" },
  { label: "公开评价", value: "public_reviews" }
];
