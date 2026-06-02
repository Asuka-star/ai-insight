# Extractor And Analyst Responsibility Refactor

## 0. Implementation Status

Status as of 2026-06-02: the backend refactor in this document has been implemented and verified with the full Maven test suite.

Completed:

- `ExtractorNode` now publishes `CompetitorFactSet`, `ExtractedFact`, and `UnknownFact` in parallel with the existing profile artifact.
- `CompetitorProfile` is now projected from accepted, evidence-bound facts instead of trusting unsupported LLM profile fields.
- `AnalystNode` consumes facts, binds claims to `factIds`, `evidenceIds`, and `chunkKeys`, and renders matrix/SWOT from claims.
- `ReviewerNode` and `CitationCoverageEvaluator` can route fact extraction issues separately from analysis or writing issues.
- Tests cover fact projection, claim-centric matrix/SWOT rendering, reviewer routing, and citation coverage behavior.

Notes:

- The public workflow and current frontend-facing `CompetitorProfile` shape are preserved for compatibility.
- Unknown or unsupported fields are surfaced as explicit `UnknownFact` or `待验证` projections.

## 1. Background

The current workflow separates evidence collection, extraction, analysis, writing, and review. This is directionally correct, but `ExtractorNode` and `AnalystNode` have overlapping responsibilities:

```text
ExtractorNode
-> reads evidence
-> extracts competitor profiles
-> includes features, pricing, personas
-> also includes strengths and weaknesses

AnalystNode
-> reads competitor profiles and evidence
-> generates claims
-> also ranks evidence, maps dimensions, builds matrix, builds SWOT
```

This means both nodes partially "read evidence and summarize meaning". The result is a blurry boundary:

```text
Extractor should answer: What facts does the evidence explicitly say?
Analyst should answer: What judgments can we make from those facts?
```

This document proposes a refactor that keeps the external agent workflow stable, while making internal responsibilities sharper.

## 2. Current Main Issue

### 2.1 Extractor Produces Some Analytical Fields

Current `CompetitorProfile` contains fields like:

```text
positioning
targetUsers
features
pricingModel
personas
strengths
weaknesses
evidenceIds
```

Fields such as `features`, `pricingModel`, and factual user/persona evidence are appropriate for extraction. But `strengths` and `weaknesses` can easily become analytical judgments.

Example:

```text
"Notion has a lightweight collaboration advantage"
```

This may be:

- A fact if a source explicitly says it.
- An analyst judgment if inferred from multiple evidence points.

When `ExtractorNode` writes this directly, `AnalystNode` may duplicate or reinterpret the same point in claims, SWOT, and matrix output.

### 2.2 Analyst Does Too Much At Once

Current `AnalystNode` is responsible for:

- Selecting evidence.
- Matching evidence to dimensions.
- Generating claims.
- Generating a competitive matrix.
- Generating SWOT.
- Understanding Reviewer repair plans.
- Handling fallback behavior.

These are related but distinct tasks. The most important analysis output should be `AnalysisClaim`; matrix and SWOT should be renderings or projections of claims, not independent analysis spaces.

### 2.3 Matrix, SWOT, And Claims May Drift

Because claims, matrix, and SWOT can be generated as separate LLM subtasks, they may diverge:

```text
claims say one thing
matrix says a slightly different thing
SWOT introduces new judgments
```

The architecture should make claims the source of truth for analysis.

## 3. Target Responsibility Boundary

### 3.1 Researcher

Researcher is responsible for evidence collection.

It should answer:

```text
What sources and chunks do we have?
What evidence is missing?
```

It should not make product strategy judgments.

### 3.2 Extractor

Extractor should become a fact extractor.

It should answer:

```text
What explicit product facts can be extracted from the evidence?
Which evidence supports each fact?
Which important fields are still unknown?
```

Extractor should not answer:

```text
Which competitor is better?
What should the user do?
What is the opportunity?
What is the strategic risk?
```

### 3.3 Analyst

Analyst should become an insight synthesizer.

It should answer:

```text
Given extracted facts and user dimensions, what claims can we responsibly make?
What are the tradeoffs, opportunities, risks, and recommendations?
How confident are we?
Which evidence supports each claim?
```

Analyst should not redo broad fact extraction from webpage text. It may inspect supporting evidence snippets, but only to validate or refine claims.

### 3.4 Writer

Writer should remain an expression layer.

It should answer:

```text
How do we turn claims and supporting artifacts into a readable report?
```

It should not introduce new facts or new analysis claims.

### 3.5 Reviewer

Reviewer should remain the quality gate.

It should answer:

```text
Are claims and report statements supported by evidence?
Are citations valid and strong enough?
Does the workflow need recollection, reanalysis, report revision, or manual input?
```

## 4. Proposed Data Model Direction

The cleanest path is to introduce a fact layer between evidence and analysis.

```text
EvidenceSource / EvidenceChunk
-> ExtractedFact / CompetitorFactSet
-> AnalysisClaim
-> Matrix / SWOT / Report
```

### 4.1 ExtractedFact

Suggested model:

```java
public class ExtractedFact {
    private String id;
    private String competitorName;
    private FactType factType;
    private String attribute;
    private String value;
    private List<String> evidenceIds;
    private List<String> chunkKeys;
    private String sourceAuthority;
    private String sourceQuality;
    private String extractionConfidence;
}
```

Suggested `FactType` values:

```text
POSITIONING
FEATURE
AI_CAPABILITY
PRICING
SECURITY
PERMISSION
INTEGRATION
TARGET_USER
CUSTOMER_SIGNAL
LIMITATION
RELEASE_UPDATE
UNKNOWN
```

Example:

```json
{
  "competitorName": "Notion",
  "factType": "AI_CAPABILITY",
  "attribute": "AI search",
  "value": "The source describes asking questions or searching across workspace knowledge.",
  "evidenceIds": ["S1"],
  "chunkKeys": ["S1-C2"],
  "sourceAuthority": "FIRST_PARTY_OFFICIAL",
  "sourceQuality": "HIGH",
  "extractionConfidence": "MEDIUM"
}
```

### 4.2 CompetitorFactSet

Suggested model:

```java
public class CompetitorFactSet {
    private String competitorName;
    private List<ExtractedFact> facts;
    private List<UnknownFact> unknowns;
    private List<String> sourceCoverageNotes;
}
```

Example:

```json
{
  "competitorName": "Confluence",
  "facts": [
    {
      "factType": "PERMISSION",
      "attribute": "admin controls",
      "value": "The source describes enterprise administration and access controls.",
      "evidenceIds": ["S5"],
      "chunkKeys": ["S5-C4"]
    }
  ],
  "unknowns": [
    {
      "field": "AI search user satisfaction",
      "reason": "No public review or user interview evidence found."
    }
  ]
}
```

### 4.3 UnknownFact

Suggested model:

```java
public class UnknownFact {
    private String competitorName;
    private String field;
    private String reason;
    private List<String> neededEvidenceTypes;
}
```

This is useful because Reviewer and Researcher can convert unknowns into targeted recollection tasks.

### 4.4 AnalysisClaim Stays The Analysis Source Of Truth

`AnalysisClaim` should remain the central analytical output, but it should reference facts as well as evidence:

```java
private List<String> factIds;
private List<String> evidenceIds;
private List<String> chunkKeys;
```

Example:

```json
{
  "type": "OPPORTUNITY",
  "content": "For teams prioritizing lightweight AI-assisted document work, Notion is a stronger experience benchmark than Confluence, while Confluence remains stronger as an enterprise governance benchmark.",
  "confidence": "MEDIUM",
  "competitorNames": ["Notion", "Confluence"],
  "factIds": ["F1", "F8", "F11"],
  "evidenceIds": ["S1", "S5"]
}
```

## 5. ExtractorNode Target Behavior

### 5.1 Input

Extractor should receive a targeted evidence pack, ideally generated by the future RAG pipeline:

```text
competitor x dimension -> selected chunks
```

Example:

```text
Competitor: Notion
Dimension: 价格策略
- [S2-C1] title=Notion Pricing | heading=Pricing > Plus | authority=FIRST_PARTY_OFFICIAL
  Text: ...
- [S2-C2] title=Notion Pricing | heading=Pricing > Business | authority=FIRST_PARTY_OFFICIAL
  Text: ...

Competitor: Notion
Dimension: 权限协作
- [S3-C4] title=Notion Enterprise | heading=Security and admin | authority=FIRST_PARTY_DOCS
  Text: ...
```

Before full RAG exists, Extractor can still use current evidence sources, but the prompt and internal representation should move toward facts.

### 5.2 Output

Extractor should output:

```text
CompetitorFactSet
ExtractedFact
UnknownFact
COMPETITOR_PROFILE or FACT_EXTRACTION artifact
```

If keeping `CompetitorProfile` for frontend compatibility, treat it as a projection of facts, not the primary extraction output.

### 5.3 Prompt Constraints

Extractor prompt should say:

```text
Only extract facts explicitly present in evidence.
Do not write strengths, opportunities, risks, or recommendations.
Do not compare competitors unless a source explicitly compares them.
If evidence is missing, create unknowns instead of filling the field.
Every fact must cite evidenceIds and preferably chunkKeys.
```

### 5.4 Field Naming Change

If `strengths` and `weaknesses` remain on `CompetitorProfile`, consider renaming or constraining them:

```text
observedAdvantages
observedLimitations
```

And require:

```text
These must be evidence-observed facts, not strategic judgments.
```

## 6. AnalystNode Target Behavior

### 6.1 Input

Analyst should receive:

```text
AnalysisRequirement
CompetitorFactSet / ExtractedFact
Evidence coverage summary
UnknownFact
Review repair plan, when rerunning
```

It should not need broad raw webpage text. It may receive short snippets for facts used in claims.

### 6.2 Output

Analyst should output:

```text
AnalysisClaim
EvidenceGap / recommended recollection needs
COMPETITIVE_MATRIX
SWOT_ANALYSIS
```

But:

```text
Matrix and SWOT should be derived from AnalysisClaim.
```

The preferred architecture is:

```text
Claim generation
-> Matrix renderer
-> SWOT renderer
```

Rather than:

```text
Claim generation
Matrix generation
SWOT generation
```

as three independent reasoning outputs.

### 6.3 Claim Requirements

Each claim should include:

```text
claim type
claim content
confidence
competitor names
fact IDs
evidence IDs
missing evidence notes, if any
```

Claims should be the only place where strategic judgments are introduced.

### 6.4 Matrix And SWOT As Projections

Matrix can be generated from claims:

```text
rows: competitors or dimensions
columns: verified strengths, limitations, tradeoffs, evidence, confidence
```

SWOT can be generated from claims:

```text
Strengths: claims of type STRENGTH
Weaknesses: claims of type WEAKNESS
Opportunities: OPPORTUNITY / RECOMMENDATION
Threats: RISK
```

If a SWOT item is not backed by a claim, it should not be generated.

## 7. Suggested Internal Service Split

Keep the external agent list stable for now. Refactor inside the nodes.

### 7.1 ExtractorNode Internal Services

```text
ExtractorEvidencePackBuilder
FactExtractionService
FactSanitizer
CompetitorProfileProjector
FactExtractionArtifactRenderer
```

Responsibilities:

- `ExtractorEvidencePackBuilder`: selects or formats evidence for extraction.
- `FactExtractionService`: calls LLM or fallback to produce facts.
- `FactSanitizer`: validates evidence IDs, chunk keys, fact types, and empties unsupported fields.
- `CompetitorProfileProjector`: derives frontend-compatible `CompetitorProfile` from facts.
- `FactExtractionArtifactRenderer`: renders extracted facts for UI.

### 7.2 AnalystNode Internal Services

```text
AnalysisContextBuilder
ClaimGenerationService
ClaimSanitizer
MatrixRenderer
SwotRenderer
AnalysisArtifactRenderer
```

Responsibilities:

- `AnalysisContextBuilder`: prepares facts, unknowns, and evidence coverage.
- `ClaimGenerationService`: generates structured claims.
- `ClaimSanitizer`: validates evidence IDs, fact IDs, confidence, and unsupported claims.
- `MatrixRenderer`: builds matrix from claims.
- `SwotRenderer`: builds SWOT from claims.
- `AnalysisArtifactRenderer`: renders analysis artifacts consistently.

## 8. Refactor Phases

### Phase 1: Clarify Contracts Without Breaking Models

Goals:

- Update prompts to make Extractor fact-only.
- Rename prompt language around strengths/weaknesses to observed facts.
- Update Analyst prompt to treat claims as the only analysis source of truth.
- Add comments/tests documenting the boundary.

Acceptance:

- Extractor prompt no longer asks for opportunities, risks, recommendations, or strategic judgments.
- Analyst prompt says matrix and SWOT must not introduce claims outside structured claims.
- Existing frontend still works.

### Phase 2: Add Fact Model In Parallel

Goals:

- Introduce `ExtractedFact`, `CompetitorFactSet`, and `UnknownFact`.
- Let Extractor populate facts while still producing `CompetitorProfile`.
- Add facts to `AnalysisRun`.

Acceptance:

- Tests can assert extracted facts separately from competitor profile projection.
- Existing report workflow remains compatible.
- Every extracted fact has valid evidence IDs.

### Phase 3: Make CompetitorProfile A Projection

Goals:

- Generate `CompetitorProfile` from `CompetitorFactSet`.
- Reduce direct LLM dependency for profile shape.
- Make frontend schema panel show facts or profile projection.

Acceptance:

- `CompetitorProfile` contains only facts supported by `ExtractedFact`.
- Unknown fields are explicit rather than filled with weak guesses.

### Phase 4: Make Analyst Claim-Centric

Goals:

- Analyst consumes facts.
- Matrix and SWOT render from claims.
- Claims reference fact IDs and evidence IDs.

Acceptance:

- No matrix or SWOT statement exists without a supporting claim.
- Claims are easier to review and rerun.
- Reviewer can identify whether an issue belongs to extraction or analysis.

### Phase 5: Integrate RAG Evidence Packs

Goals:

- Use structured chunks and hybrid retrieval to build Extractor evidence packs.
- Select evidence per competitor and dimension.
- Record selected chunk keys in traces.

Acceptance:

- Extractor input is traceable and bounded.
- Pricing extraction sees pricing chunks.
- Permission/security extraction sees security/admin chunks.
- Weak or missing evidence becomes `UnknownFact`.

## 9. Reviewer Impact

The refactor makes Reviewer more precise:

```text
If a fact is wrong or unsupported -> target Extractor.
If a claim over-interprets facts -> target Analyst.
If report wording exaggerates a valid claim -> target Writer.
If evidence is missing -> target Researcher.
```

This gives `ReviewDecision.targetAgent` a cleaner meaning.

Example:

```text
Extractor issue:
"Pricing plan value cites a third-party article while official pricing page exists."
targetAgent=EXTRACTOR

Analyst issue:
"Claim says Confluence is better for governance, but extracted facts only show admin controls, not comparative superiority."
targetAgent=ANALYST

Writer issue:
"Report states 'clearly superior' while claim confidence is MEDIUM."
targetAgent=WRITER
```

## 10. Example End-To-End After Refactor

User request:

```text
Analyze Notion and Confluence for AI document collaboration, focusing on AI search, permissions, and pricing.
```

Researcher:

```text
S1 Notion AI page
S2 Notion pricing page
S3 Notion enterprise/security page
S4 Confluence AI docs
S5 Confluence pricing page
S6 Confluence permissions docs
```

Extractor facts:

```json
[
  {
    "competitorName": "Notion",
    "factType": "AI_CAPABILITY",
    "attribute": "workspace AI search",
    "value": "The source describes AI assistance across workspace knowledge.",
    "evidenceIds": ["S1"]
  },
  {
    "competitorName": "Confluence",
    "factType": "PERMISSION",
    "attribute": "enterprise admin controls",
    "value": "The source describes admin or permission controls for enterprise collaboration.",
    "evidenceIds": ["S6"]
  }
]
```

Analyst claims:

```json
[
  {
    "type": "OPPORTUNITY",
    "content": "Notion should be used as the benchmark for lightweight AI-assisted document experience, while Confluence should be used as the benchmark for enterprise governance and permissions.",
    "confidence": "MEDIUM",
    "factIds": ["F1", "F2"],
    "evidenceIds": ["S1", "S6"]
  }
]
```

Writer:

```text
Turns the claim into a readable report section with citations.
```

Reviewer:

```text
Checks whether the claim is supported by F1/F2 and S1/S6, and whether the report overstates confidence.
```

## 11. Tests To Add

Extractor tests:

- Extractor does not produce strategic recommendations.
- Extractor filters invented evidence IDs.
- Extractor creates unknowns for missing pricing/security/persona facts.
- CompetitorProfile projection only uses extracted facts.

Analyst tests:

- Analyst claims reference valid fact IDs and evidence IDs.
- Matrix contains no unsupported statements outside claims.
- SWOT contains no unsupported statements outside claims.
- Weak evidence lowers confidence or creates evidence gaps.

Reviewer tests:

- Unsupported fact targets Extractor.
- Over-interpreted claim targets Analyst.
- Exaggerated report wording targets Writer.
- Missing evidence target remains Researcher.

## 12. Recommended First Step

Start with Phase 1:

```text
Clarify prompts and tests without changing the data model.
```

Then implement Phase 2:

```text
Add ExtractedFact and CompetitorFactSet in parallel with existing CompetitorProfile.
```

This keeps the frontend and current workflow stable while making the responsibility boundary observable and testable.
