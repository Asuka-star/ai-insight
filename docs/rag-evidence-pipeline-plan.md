# RAG Evidence Pipeline Enhancement Plan

## 0. Implementation Status

Status as of 2026-06-02: the backend pipeline work in this document has been implemented and verified with the full Maven test suite.

Completed:

- Source classification now separates `sourceType`, `sourceAuthority`, and `sourceQuality`, including third-party pricing references versus official pricing pages.
- Evidence chunks now carry section-style metadata such as `headingPath`, `contentKind`, `sourceType`, `sourceAuthority`, and `sourceQuality`.
- Embedding support and pgvector projection are available with keyword retrieval fallback when vectors are unavailable.
- Retrieval scoring now combines semantic/vector candidates, keyword evidence, source authority, quality, and content kind boosts.
- `ExtractorNode` receives targeted evidence through RAG-style evidence packs and records selected chunk keys on extracted facts.
- Reviewer/citation coverage rules now check weak-source usage for sensitive claims such as pricing and security.

Notes:

- The repository does not currently contain a separate frontend app, so the planned frontend evidence-panel display is not implemented in this backend-only scope.
- Existing source-level citations are preserved while chunk-level metadata is added for traceability.

## 1. Background

The current evidence pipeline already supports user-provided URLs, search-derived URLs, webpage fetching, evidence source persistence, simple chunking, keyword retrieval, and downstream agent usage. It is enough for an MVP, but it has several precision issues:

- Source classification mixes content type and authority. For example, third-party pages with `pricing` in the URL can become pricing-like evidence even when they are only commentary or comparisons.
- Chunking is fixed-length text slicing. It can split tables, FAQ answers, pricing plans, and section context.
- Retrieval is keyword based. It cannot reliably find semantically relevant chunks such as "enterprise governance" when the query says "permissions".
- `ExtractorNode` currently receives short snippets and the beginning of each source, instead of the most relevant chunks for each competitor and dimension.

This document describes a staged plan to make the evidence pipeline closer to a practical RAG system while preserving the project's current observable, citation-first workflow.

## 2. Current Flow

```text
User source URLs / user evidence / search results
-> SourceCollectionService
-> WebPageFetchService
-> EvidenceSource
-> EvidenceChunkService
-> EvidenceChunk
-> ExtractorNode
-> CompetitorProfile
-> AnalystNode
-> AnalysisClaim
-> WriterNode
-> REPORT_DRAFT
-> ReviewerNode
-> ReviewDecision
```

Important current behavior:

- User-provided URLs are retained even when fetching fails, so the frontend can explain the issue.
- Search-derived URLs are stricter: if the page cannot be fetched into usable text, the search result is dropped.
- `WebPageFetchService` extracts `main`, `article`, `[role=main]`, `#content`, `.content`, `#main`, `.main`, then falls back to body text and metadata.
- `PageQualityEvaluator` rejects HTTP errors, non-HTML content, empty text, thin text, anti-bot pages, and login-gated pages.
- `EvidenceChunkService` slices text into 420-character chunks with 80-character overlap.
- `EvidenceRetrievalService` uses keyword and Chinese-bigram matching, not embeddings.
- `ExtractorNode` passes at most 24 sources to the LLM, with each source represented roughly by title, type, quality, snippet, and the first part of raw text.

## 3. Problems To Solve

### 3.1 Source Classification Is Too Coarse

Current `sourceType` combines multiple meanings:

- What the page is about: pricing, docs, reviews, release notes.
- Who published it: official site, third-party site, community forum.
- How trustworthy it is: high, medium, low, internal-only.

This causes ambiguity. A third-party "Notion pricing comparison" page is not equivalent to Notion's official pricing page, even if both mention pricing.

### 3.2 Fixed-Length Chunking Loses Structure

Fixed-length chunks may:

- Split a pricing table across chunks.
- Separate FAQ questions from their answers.
- Lose heading context such as "Enterprise plan" or "Admin permissions".
- Mix unrelated navigation, boilerplate, and content.
- Make it hard for Extractor to know whether a statement belongs to pricing, security, integrations, AI search, or reviews.

### 3.3 Retrieval Is Not Yet RAG-Grade

Keyword matching is simple and transparent, but misses semantic matches. Examples:

- Query: "权限治理"; relevant text: "admin controls", "SCIM", "SAML", "role-based access".
- Query: "AI 搜索"; relevant text: "ask questions across workspace knowledge".
- Query: "企业版价格"; relevant text: "contact sales", "Enterprise plan", "annual billing".

### 3.4 Extractor Context Is Not Targeted Enough

`ExtractorNode` currently sees source-level snippets rather than dimension-targeted evidence. If pricing information appears in the middle of a long page, the LLM may never see it. Giving more raw text is not enough; we should give more relevant text.

## 4. Target Design

The target design should separate evidence collection, evidence classification, evidence segmentation, retrieval, and extraction.

```text
URL
-> Fetch webpage
-> Extract structured page sections
-> Classify source authority and content kind
-> Build structured EvidenceChunks
-> Embed chunks
-> Hybrid retrieve chunks by competitor and dimension
-> Feed selected evidence pack into ExtractorNode
-> Validate citations and schema
```

## 5. Source Classification Redesign

### 5.1 Split Source Type And Authority

Add separate concepts:

```text
sourceType      What kind of content this is
sourceAuthority Who published it / how authoritative it is
sourceQuality   How usable and reliable it is for this run
```

Suggested `sourceType` values:

```text
official_site
product_docs
pricing_page
release_notes
security_docs
integration_docs
technical_blog
public_review
community_discussion
third_party_article
third_party_pricing_reference
analyst_report
video
user_interview
user_survey
user_note
unknown
```

Suggested `sourceAuthority` values:

```text
FIRST_PARTY_OFFICIAL
FIRST_PARTY_DOCS
FIRST_PARTY_BLOG
THIRD_PARTY_AUTHORITATIVE
THIRD_PARTY_GENERAL
COMMUNITY
USER_PROVIDED
INTERNAL_ONLY
SEARCH_SNIPPET
UNKNOWN
```

Suggested `sourceQuality` remains:

```text
HIGH
MEDIUM
LOW
INTERNAL_ONLY
UNUSABLE
```

### 5.2 Classification Examples

```text
https://www.notion.com/pricing
sourceType=pricing_page
sourceAuthority=FIRST_PARTY_OFFICIAL
sourceQuality=HIGH

https://www.atlassian.com/software/confluence/pricing
sourceType=pricing_page
sourceAuthority=FIRST_PARTY_OFFICIAL
sourceQuality=HIGH

https://example-blog.com/notion-pricing-comparison
sourceType=third_party_pricing_reference
sourceAuthority=THIRD_PARTY_GENERAL
sourceQuality=MEDIUM

https://reddit.com/r/Notion/comments/...pricing...
sourceType=community_discussion
sourceAuthority=COMMUNITY
sourceQuality=LOW

User uploaded interview note
sourceType=user_interview
sourceAuthority=USER_PROVIDED
sourceQuality=INTERNAL_ONLY
```

### 5.3 Decision Rules

Pricing facts should prefer:

```text
FIRST_PARTY_OFFICIAL + pricing_page
```

Third-party pricing pages can support market commentary, but should not be treated as primary price truth unless no official source exists and the report clearly labels it as secondary.

User reviews should prefer:

```text
public_review / community_discussion
```

Product capability facts should prefer:

```text
FIRST_PARTY_OFFICIAL + official_site/product_docs
FIRST_PARTY_DOCS + product_docs/security_docs/integration_docs
```

## 6. Structured Chunking Plan

### 6.1 Replace Fixed Character Slicing With Section-Aware Chunking

Instead of slicing raw text every 420 characters, parse HTML into logical units:

```text
page
-> heading hierarchy
-> sections
-> paragraphs
-> lists
-> tables
-> FAQ pairs
```

Each chunk should preserve:

```json
{
  "chunkKey": "S2-C3",
  "sourceCitationKey": "S2",
  "title": "Notion Pricing",
  "url": "https://www.notion.com/pricing",
  "headingPath": ["Pricing", "Business plan"],
  "text": "Business plan includes...",
  "contentKind": "pricing",
  "sourceType": "pricing_page",
  "sourceAuthority": "FIRST_PARTY_OFFICIAL",
  "sourceQuality": "HIGH"
}
```

### 6.2 Chunk Construction Rules

Use these rules initially:

- Remove boilerplate elements: nav, footer, script, style, forms, cookie banners where possible.
- Preserve heading path from `h1` to `h4`.
- Keep tables together when reasonable.
- Keep list groups together when they share a heading.
- Keep FAQ question and answer together.
- Merge very short adjacent paragraphs under the same heading.
- Split very long sections by paragraph or sentence boundary.
- Target chunk size should be token-aware later, but initially use a character range such as 700-1200 characters.
- Keep overlap only when splitting long sections, not between unrelated sections.

### 6.3 Content Kind Classification

Each chunk can get a `contentKind`, derived from heading path, URL, title, and text:

```text
pricing
security
permission
ai_feature
integration
release_note
customer_story
public_review
faq
general_product
unknown
```

This helps retrieval and Extractor context assembly.

## 7. Embedding And Retrieval Plan

### 7.1 Add Embeddings To EvidenceChunk

Extend chunk persistence to include:

```text
embedding vector
embeddingModel
embeddedAt
embeddingTextHash
```

Embedding input should combine:

```text
source title
heading path
content kind
chunk text
```

Example embedding text:

```text
Title: Notion Pricing
Source type: pricing_page
Authority: FIRST_PARTY_OFFICIAL
Heading: Pricing > Business plan
Text: Business plan includes...
```

### 7.2 Storage

The project already uses PostgreSQL. The recommended path is:

```text
PostgreSQL + pgvector
```

The existing `evidence_chunk` projection table can be extended or paired with a new vector table:

```sql
evidence_chunk_embedding (
  chunk_id uuid primary key,
  run_id uuid not null,
  source_citation_key varchar(32) not null,
  embedding vector(...),
  embedding_model varchar(128),
  embedding_text_hash varchar(128),
  embedded_at timestamptz
)
```

### 7.3 Hybrid Retrieval

Do not rely on embeddings alone. Use hybrid scoring:

```text
finalScore =
  semanticScore
  + keywordScore
  + sourceAuthorityBoost
  + sourceQualityBoost
  + contentKindBoost
  + competitorCoverageBoost
```

Example:

```text
Query: "Notion 企业权限"

Boost chunks when:
- sourceAuthority is FIRST_PARTY_OFFICIAL or FIRST_PARTY_DOCS
- contentKind is permission/security
- headingPath contains enterprise/admin/security/permissions
- text mentions Notion
```

### 7.4 Retrieval APIs

Current endpoint:

```http
GET /api/analysis-runs/{runId}/retrieval?query=价格 套餐&topK=5
```

Possible future options:

```http
GET /api/analysis-runs/{runId}/retrieval?query=企业权限&competitor=Notion&dimension=权限协作&mode=hybrid&topK=8
```

## 8. ExtractorNode Input Redesign

### 8.1 Current Extractor Input

Current `ExtractorNode` builds an evidence block roughly like:

```text
[S1] title=... | type=... | quality=...
snippet=...
raw=...
```

It limits raw text per source. This is simple but not targeted.

### 8.2 Target Extractor Input

Before calling the LLM, assemble evidence by:

```text
competitor x dimension
```

Example:

```text
Competitor: Notion
Dimension: AI 搜索
- [S1-C2] title=Notion AI | heading=AI Search | authority=FIRST_PARTY_OFFICIAL
  Text: ...
- [S4-C1] title=Notion user review | heading=Search experience | authority=COMMUNITY
  Text: ...

Competitor: Notion
Dimension: 价格策略
- [S2-C3] title=Notion Pricing | heading=Business plan | authority=FIRST_PARTY_OFFICIAL
  Text: ...

Competitor: Confluence
Dimension: 权限协作
- [S8-C2] title=Confluence permissions | heading=Admin controls | authority=FIRST_PARTY_DOCS
  Text: ...
```

### 8.3 Benefits

- LLM sees relevant evidence instead of arbitrary page starts.
- Pricing extraction can target pricing chunks.
- Permission extraction can target security/admin chunks.
- Citations become more precise: source-level `[S2]` can later be refined to chunk-level references if needed.
- Token budget is spent on useful evidence.

### 8.4 Extraction Output Constraints

Keep strict JSON output, but add stronger source constraints:

- `pricing` fields should use official pricing chunks when available.
- `features` should cite product/docs chunks.
- `personas` can cite official target-user pages, user interviews, surveys, or public reviews.
- Weak or third-party evidence must downgrade certainty.
- Unknown facts should be marked as `待验证`.

## 9. Reviewer Enhancements

Reviewer should understand source authority and content kind.

Recommended rules:

- A price value from `THIRD_PARTY_GENERAL` should not be treated as a strong pricing fact if official pricing exists.
- Claims about enterprise security should require `security_docs`, `product_docs`, or official pages.
- Public sentiment claims should require `public_review`, `community_discussion`, `user_interview`, or `user_survey`.
- If a report cites a LOW quality source for a HIGH confidence claim, downgrade or flag it.

Example finding:

```json
{
  "severity": "HIGH",
  "category": "weak_pricing_source",
  "message": "The report states a specific pricing fact but cites a third-party pricing comparison instead of the official pricing page.",
  "recommendation": "Use first-party pricing evidence or mark the pricing fact as待验证."
}
```

## 10. Phased Implementation

### Phase 1: Source Classification Cleanup

Goals:

- Split `sourceType`, `sourceAuthority`, and `sourceQuality`.
- Avoid treating third-party pricing references as official pricing pages.
- Update source ranking logic in Analyst and Reviewer.

Implementation candidates:

- Update `EvidenceSource` model.
- Update `SourceTypeClassifier`.
- Update `SourceCollectionService.fromUrl`.
- Update `PostgresAnalysisRunRepository` detail projection.
- Update frontend evidence panel to display authority.
- Add tests for official pricing, third-party pricing, public review, docs, and community URLs.

Acceptance:

- Official pricing page is `pricing_page + FIRST_PARTY_OFFICIAL + HIGH`.
- Third-party pricing article is `third_party_pricing_reference + THIRD_PARTY_GENERAL + MEDIUM`.
- Reddit/G2 pricing discussions are not promoted to official pricing evidence.

### Phase 2: Structured Chunking

Goals:

- Replace fixed slicing with section-aware chunking.
- Preserve heading path and content kind.
- Keep tables, lists, and FAQ pairs coherent.

Implementation candidates:

- Add a `PageSectionExtractor`.
- Extend `EvidenceChunk` with `headingPath`, `contentKind`, `sourceType`, `sourceAuthority`, `sourceQuality`.
- Update `EvidenceChunkService`.
- Add tests with HTML containing headings, pricing tables, FAQ, docs sections.

Acceptance:

- Pricing table rows remain in coherent chunks.
- FAQ question and answer are not split.
- Chunks include heading path.
- Chunk count remains bounded.

### Phase 3: Embeddings And Hybrid Retrieval

Goals:

- Add embedding generation for evidence chunks.
- Store embeddings in pgvector.
- Add hybrid retrieval by semantic score, keyword score, quality, authority, and content kind.

Implementation candidates:

- Add `EmbeddingClient` abstraction.
- Add embedding config and fallback behavior.
- Add vector table migration or repository initialization.
- Add `HybridEvidenceRetrievalService`.
- Keep current keyword retrieval as fallback.

Acceptance:

- Retrieval works without embedding key using keyword fallback.
- Retrieval uses embeddings when configured.
- Queries like "企业权限" recall permission/security chunks even when exact terms differ.

### Phase 4: Extractor Evidence Pack

Goals:

- Assemble Extractor input by competitor and dimension.
- Feed targeted chunks, not arbitrary source starts.
- Keep strict citation validation.

Implementation candidates:

- Add `ExtractorEvidencePackBuilder`.
- Retrieve top chunks per competitor and dimension.
- Include fallback coverage for each competitor if retrieval is sparse.
- Update Extractor prompt to explain evidence pack structure.
- Add trace snapshots showing selected chunks.

Acceptance:

- Extractor input clearly groups evidence by competitor and dimension.
- Pricing fields use pricing chunks when present.
- Permission/security fields use security/admin chunks when present.
- LLM token budget is bounded and observable in trace.

### Phase 5: Reviewer Evidence-Aware Rules

Goals:

- Reviewer flags weak-source usage for strong claims.
- Reviewer understands official vs third-party vs community evidence.
- ReviewDecision repair tasks become more targeted.

Implementation candidates:

- Extend `CitationCoverageEvaluator`.
- Add source-authority checks per claim type and dimension.
- Add repair tasks that request specific content kind and authority.

Acceptance:

- Specific pricing claims without official pricing source are flagged.
- Security claims backed only by community posts are downgraded or flagged.
- Public sentiment claims can use reviews/interviews without being incorrectly penalized as weak product facts.

## 11. Data Model Sketch

### EvidenceSource Additions

```java
private String sourceAuthority;
private String canonicalHost;
private String publisherName;
private String contentLanguage;
```

### EvidenceChunk Additions

```java
private List<String> headingPath;
private String contentKind;
private String sourceType;
private String sourceAuthority;
private String sourceQuality;
private String textHash;
private String embeddingModel;
private Instant embeddedAt;
```

## 12. Risks

- Embedding costs and latency may slow down workflow startup.
- More chunk metadata means repository and frontend changes become broader.
- Source classification can never be perfect with URL rules only.
- Page structures differ widely; section extraction needs robust fallbacks.
- Too much evidence in Extractor prompts can increase cost without improving accuracy.

Mitigations:

- Keep keyword retrieval fallback.
- Add feature flags for embedding and hybrid retrieval.
- Use bounded topK per competitor and dimension.
- Preserve existing source-level citations while optionally adding chunk-level trace detail.
- Add focused tests before changing prompts.

## 13. Recommended Next Step

Start with Phase 1 and Phase 2 before embeddings.

Reason:

- Better source authority and better chunks improve the pipeline immediately.
- They reduce garbage-in before adding semantic retrieval.
- Embedding quality depends heavily on chunk quality.
- Extractor input redesign becomes much easier once chunks have headings and content kinds.

Suggested first concrete task:

```text
Implement sourceAuthority and improve SourceTypeClassifier tests.
```

Then:

```text
Implement section-aware EvidenceChunkService while preserving current public behavior as fallback.
```
