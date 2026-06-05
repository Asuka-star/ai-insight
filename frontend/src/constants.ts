import type { AgentName, ArtifactType } from "./types";

export const AGENTS: AgentName[] = [
  "CLARIFIER",
  "RESEARCHER",
  "EXTRACTOR",
  "ANALYST",
  "WRITER",
  "REVIEWER"
];

export const AGENT_LABELS: Record<AgentName, string> = {
  CLARIFIER: "范围澄清",
  RESEARCHER: "资料采集",
  EXTRACTOR: "结构化抽取",
  ANALYST: "竞品分析",
  WRITER: "报告撰写",
  REVIEWER: "事实质检"
};

export const AGENT_STAGE: Record<AgentName, string> = {
  CLARIFIER: "范围确认",
  RESEARCHER: "资料采集",
  EXTRACTOR: "结构化抽取",
  ANALYST: "竞品分析",
  WRITER: "报告撰写",
  REVIEWER: "事实质检"
};

export const ARTIFACT_LABELS: Record<ArtifactType, string> = {
  CLARIFICATION_BRIEF: "范围摘要",
  SOURCE_LIST: "资料清单",
  RESEARCH_PLAN: "调研计划",
  FACT_EXTRACTION: "事实抽取",
  COMPETITOR_PROFILE: "竞品 结构化信息",
  COMPETITIVE_MATRIX: "横向矩阵",
  SWOT_ANALYSIS: "SWOT 分析",
  REPORT_DRAFT: "报告草稿",
  REVIEW_FINDINGS: "复核结果",
  FINALIZATION_NOTE: "封版说明",
  FINAL_REPORT: "最终报告"
};
