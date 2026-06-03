# User Document RAG Development Plan

## 1. Background

AI Insight already has a citation-first evidence pipeline:

```text
EvidenceSource
-> EvidenceChunk
-> optional embedding
-> EvidenceRetrievalService
-> Extractor / Analyst / Writer / Reviewer
```

The current pipeline mainly accepts:

- User-provided public URLs.
- User-provided text evidence through `/api/analysis-runs/{runId}/evidence`.
- Search-derived public pages when a search provider is configured.

The next enhancement is to let users upload documents, such as product briefs, interview notes, survey summaries, pricing documents, internal research notes, and competitor comparison files. These uploaded documents should become first-class evidence sources. Downstream agents should be able to retrieve and cite them just like public web evidence.

This document describes the development plan for user document ingestion and RAG integration.

## 2. Goals

The goal is not to build a separate RAG subsystem. The goal is to extend the existing evidence pipeline so uploaded documents become traceable, chunked, retrievable, and citable evidence.

Primary goals:

- Support user-uploaded documents as evidence sources in an analysis run.
- Parse document text into `EvidenceSource.rawText`.
- Chunk parsed text through the existing `EvidenceChunkService`.
- Embed chunks through the existing `EvidenceEmbeddingService` when embeddings are configured.
- Store document evidence inside the existing `AnalysisRun` aggregate and PostgreSQL detail tables.
- Let Extractor, Analyst, Writer, and Reviewer use uploaded documents through existing retrieval and citation mechanisms.
- Show uploaded document evidence in the frontend Evidence panel.
- Preserve safety and compliance metadata for sensitive or internal documents.

Secondary goals:

- Make uploaded document citation keys stable, for example `[S7]`.
- Support manual rerun after document upload.
- Allow users to mark documents as sensitive or internal-only.
- Provide enough metadata for future document-level filtering and retrieval.

## 3. Non-Goals For The First Version

The first version should stay focused. Avoid expanding scope into these areas:

- Cross-run document libraries.
- Long-term organization-level knowledge bases.
- User authentication and document ownership.
- OCR for scanned PDFs.
- Full document version diffing.
- Fine-grained permission inheritance from enterprise document systems.
- Conversational Q&A over all uploaded documents.
- A separate vector database beyond the existing PostgreSQL/pgvector path.

These can be added later after the per-run ingestion path is stable.

## 4. Current Relevant Code

Backend entry points:

- `AnalysisRunController`
  - Existing REST endpoints for run creation, evidence input, context input, rerun, retrieval, and SSE.
- `AnalysisWorkflowService`
  - Owns run lifecycle and currently has `addEvidence`.
- `SourceCollectionService`
  - Converts user-provided evidence into `EvidenceSource`.
- `EvidenceChunkService`
  - Converts `EvidenceSource` text into `EvidenceChunk`.
- `EvidenceEmbeddingService`
  - Adds embeddings to chunks when embedding config is available.
- `EvidenceRetrievalService`
  - Performs keyword and optional vector retrieval.
- `PostgresAnalysisRunRepository`
  - Saves the full `AnalysisRun` JSONB payload and refreshes detail tables.

Model classes:

- `AnalysisRun`
- `EvidenceSource`
- `EvidenceChunk`
- `UserProvidedEvidence`
- `AnalysisArtifact`
- `ResearchPackage`

Frontend entry points:

- `frontend/src/App.tsx`
- `frontend/src/api.ts`
- `frontend/src/types.ts`
- `frontend/src/components/EvidencePanel.tsx`
- `frontend/src/components/ContextPanel.tsx`

## 5. Target User Flow

### 5.1 Upload Before Starting Analysis

```text
User creates run
-> User confirms scope
-> User uploads documents
-> Backend parses and chunks documents
-> EvidencePanel shows new citation sources
-> User starts workflow
-> Researcher/Extractor/Analyst/Writer can use document evidence
```

### 5.2 Upload After Analysis Has Finished

```text
User uploads an extra document
-> Backend adds new EvidenceSource and EvidenceChunks
-> Frontend shows the new source
-> System recommends rerunning RESEARCHER, EXTRACTOR, or downstream agents
-> User manually reruns the relevant agent
```

### 5.3 Sensitive Document

```text
User uploads document and marks it sensitive
-> EvidenceSource.sourceAuthority = INTERNAL_ONLY
-> EvidenceSource.sourceQuality = INTERNAL_ONLY
-> complianceNote records internal-only handling
-> Writer may cite it, but report should avoid presenting it as public evidence
```

## 6. Target Architecture

```text
Multipart document upload
-> DocumentUploadController endpoint
-> DocumentIngestionService
   -> DocumentTextExtractor
   -> DocumentTextCleaner
   -> EvidenceSource factory
   -> EvidenceChunkService
   -> EvidenceEmbeddingService
-> AnalysisRunRepository.save
-> AnalysisEventBroker.publish
-> Frontend refresh / SSE snapshot
```

The important design choice is that uploaded documents should enter the same evidence chain as other sources:

```text
uploaded file
-> EvidenceSource
-> EvidenceChunk
-> retrieval
-> citation
-> Reviewer validation
```

Agents should not need a new "document" channel unless a later version needs special document-only behavior.

## 7. API Design

### 7.1 Upload Document Endpoint

Add an endpoint under the existing run resource:

```http
POST /api/analysis-runs/{runId}/documents
Content-Type: multipart/form-data
```

Form fields:

```text
file: MultipartFile, required
title: string, optional
sourceType: string, optional, default=user_document
sensitive: boolean, optional, default=false
notes: string, optional
```

Response:

```text
AnalysisRun
```

Recommended controller method:

```java
@PostMapping(path = "/{runId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public AnalysisRun uploadDocument(
        @PathVariable UUID runId,
        @RequestPart("file") MultipartFile file,
        @RequestParam(required = false) String title,
        @RequestParam(required = false) String sourceType,
        @RequestParam(defaultValue = "false") boolean sensitive,
        @RequestParam(required = false) String notes) {
    return workflowService.addDocument(runId, file, title, sourceType, sensitive, notes);
}
```

### 7.2 Optional Retrieval Filter Endpoint

The existing retrieval endpoint can remain unchanged for the first version:

```http
GET /api/analysis-runs/{runId}/retrieval?query=...&topK=5
```

Later, add filters only when needed:

```http
GET /api/analysis-runs/{runId}/retrieval?query=...&sourceType=user_document&topK=5
```

## 8. Backend Service Design

### 8.1 New DocumentIngestionService

Create:

```text
src/main/java/com/aiinsight/service/DocumentIngestionService.java
```

Responsibilities:

- Validate uploaded file.
- Detect supported document type.
- Extract plain text.
- Normalize title and metadata.
- Create `EvidenceSource`.
- Chunk and embed the source.
- Update `AnalysisRun`.
- Add recommended rerun actions.

Suggested method:

```java
public AnalysisRun ingest(
        AnalysisRun run,
        MultipartFile file,
        String title,
        String sourceType,
        boolean sensitive,
        String notes)
```

This service should not start the workflow. It only adds evidence and returns the updated run.

### 8.2 New DocumentTextExtractor

Create:

```text
src/main/java/com/aiinsight/service/DocumentTextExtractor.java
```

Responsibilities:

- Extract text from supported file types.
- Return a normalized result object.
- Hide parser library details from `DocumentIngestionService`.

Suggested return type:

```java
public record ExtractedDocumentText(
        String title,
        String mediaType,
        String originalFilename,
        String text,
        Map<String, String> metadata
) {}
```

First-version supported formats:

| Format | Strategy |
| --- | --- |
| `.txt` | Read UTF-8 text |
| `.md` | Read UTF-8 text |
| `.pdf` | Apache PDFBox or Apache Tika |
| `.docx` | Apache POI or Apache Tika |

Recommended dependency choice:

- Prefer Apache Tika if the project wants one parser facade for PDF, DOCX, TXT, and Markdown.
- Prefer PDFBox + POI if the project wants smaller and more explicit dependencies.

For this project, Apache Tika is the simpler first implementation because the feature is ingestion-focused rather than layout-focused.

### 8.3 New DocumentUploadRequest Metadata

Because multipart uploads do not map as cleanly as JSON DTOs, metadata can be passed with request params first. If metadata grows, add:

```text
src/main/java/com/aiinsight/dto/UploadDocumentMetadataRequest.java
```

But for the first version, request params are enough.

## 9. EvidenceSource Mapping

Uploaded documents should become `EvidenceSource` instances.

Suggested mapping:

```text
citationKey       next available S number
title             user title or extracted title or original filename
url               user-document://{documentId}
sourceType        user_document, user_pdf, user_docx, user_note, user_interview, user_survey
collectionStatus  USER_PROVIDED
freshness         INTERNAL_ONLY when sensitive, otherwise USER_PROVIDED
sourceAuthority   INTERNAL_ONLY when sensitive, otherwise USER_PROVIDED
sourceQuality     INTERNAL_ONLY when sensitive, otherwise HIGH or MEDIUM
snippet           first useful text snippet
rawText           extracted cleaned text
complianceNote    explains user-provided and sensitive handling
```

Recommended source type normalization:

```text
sourceType input blank -> user_document
sourceType interview -> user_interview
sourceType survey -> user_survey
sourceType note -> user_note
sourceType pdf -> user_pdf
sourceType docx -> user_docx
```

The system should not expose local file paths. Store only original filename and generated internal URL.

## 10. Citation Key Strategy

Use the same citation sequence as existing evidence:

```text
S1, S2, S3 ...
```

Implementation:

- Reuse or extract the existing `nextCitationKey` logic from `AnalysisWorkflowService`.
- Avoid assigning citation keys in multiple places with duplicate logic.
- Consider moving citation allocation to a small helper service later:

```text
EvidenceCitationService.nextCitationKey(AnalysisRun run)
```

For the first version, keeping the helper private inside `AnalysisWorkflowService` is acceptable if implementation remains small.

## 11. Chunking And Retrieval

### 11.1 First-Version Chunking

The current `EvidenceChunkService` should be reused.

For uploaded documents:

```text
EvidenceSource.rawText
-> EvidenceChunkService.chunk(List.of(source))
-> EvidenceEmbeddingService.embedChunks(...)
-> run.getEvidenceChunks().addAll(...)
```

This keeps behavior consistent with web and text evidence.

### 11.2 Document Metadata On Chunks

Chunks should inherit:

- `sourceType`
- `sourceAuthority`
- `sourceQuality`
- `title`
- `url`

If the existing chunk service already copies these fields, no new behavior is needed. If not, add tests and fix it there.

### 11.3 Future Section-Aware Document Chunking

After the first version, improve document chunking:

- Preserve Markdown headings.
- Preserve DOCX headings when available.
- Preserve PDF page numbers.
- Add chunk metadata:
  - `pageNumber`
  - `sectionTitle`
  - `headingPath`
  - `contentKind`

These fields will help Reviewer locate issues and help frontend show document snippets more clearly.

## 12. Workflow Integration

### 12.1 AnalysisWorkflowService

Add:

```java
public AnalysisRun addDocument(UUID runId, MultipartFile file, String title, String sourceType, boolean sensitive, String notes)
```

Expected behavior:

- Load run.
- Reject upload while run is `RUNNING`, `REVIEWING`, `REVISING`, or `CANCELLED`.
- Allow upload when run is `AWAITING_CONFIRMATION`, `PENDING`, `NEEDS_USER_INPUT`, `FAILED`, or `SUCCEEDED`.
- Ingest document.
- Save run.
- Publish event, for example `document_added`.
- Return updated run.

Status should not automatically change to `RUNNING`.

If run has already succeeded:

- Keep status as `SUCCEEDED` or set to `NEEDS_USER_INPUT`.
- Recommended first version: keep existing status and add `recommendedActions`.
- Example recommended action:

```text
用户文档 [S7] 已加入证据链。建议重跑 RESEARCHER 或 EXTRACTOR 以刷新后续产物。
```

### 12.2 ResearcherNode

The first version can rely on existing evidence lists. But add prompt/context improvements later:

- Mention user-uploaded document sources in research plan.
- Treat internal user documents as high-authority for user-specific claims.
- Avoid asking for public sources when the uploaded document already covers first-party interview or survey evidence.

### 12.3 ExtractorNode

Expected first-version behavior:

- Uploaded document chunks are included in retrieval candidates.
- Extractor can bind facts to document citation keys and chunk keys.

Possible improvement:

- When `sourceType` starts with `user_`, prioritize chunks when extracting user research, interview, survey, internal requirements, and product constraints.

### 12.4 AnalystNode

Expected first-version behavior:

- Claims may use document evidence IDs.

Important rule:

- If a claim relies on sensitive/internal-only documents, the claim should not present the evidence as public market proof.
- Wording should distinguish:

```text
根据用户提供资料...
```

from:

```text
公开资料显示...
```

### 12.5 WriterNode

Writer prompt should eventually include a constraint:

```text
If a citation source is INTERNAL_ONLY or USER_PROVIDED, describe it as user-provided evidence instead of public evidence.
```

This prevents internal documents from being misrepresented as external validation.

### 12.6 ReviewerNode

Reviewer should treat document evidence carefully:

- Internal-only evidence can support user-specific findings.
- Internal-only evidence should not be treated as public market proof.
- If a public claim only cites internal evidence, Reviewer may create a medium finding asking for public corroboration.

First version can rely on existing source authority and quality scoring. Add stricter Reviewer rules later.

## 13. Frontend Design

### 13.1 Upload Entry Point

Add document upload controls in the left-side evidence/context area.

Fields:

- File selector.
- Optional title.
- Source type select:
  - Document
  - Interview note
  - Survey summary
  - Product brief
  - Pricing document
  - Other note
- Sensitive/internal-only checkbox.
- Upload button.

Preferred placement:

- Near existing "补充资料" panel.
- Do not hide it inside history or metrics panels.

### 13.2 API Client

Add to `frontend/src/api.ts`:

```ts
export async function uploadDocument(
  runId: string,
  payload: {
    file: File;
    title?: string;
    sourceType?: string;
    sensitive?: boolean;
    notes?: string;
  }
): Promise<AnalysisRun>
```

Use `FormData`.

### 13.3 Types

The existing `EvidenceSource` type may need fields:

```ts
sourceAuthority?: string;
sourceQuality?: string;
failureReason?: string;
contentHash?: string;
cacheHit?: boolean;
```

If already present, reuse them.

Optional new type:

```ts
export interface UploadDocumentRequest {
  file: File;
  title?: string;
  sourceType?: string;
  sensitive?: boolean;
  notes?: string;
}
```

### 13.4 EvidencePanel Display

Evidence panel should clearly show uploaded document sources:

- Citation key.
- Document title.
- Original filename if available.
- Source type.
- Internal-only badge if sensitive.
- Snippet.
- Chunk count if available later.

Avoid showing local file paths.

### 13.5 UX States

Need states:

- Uploading.
- Upload succeeded.
- Upload failed because unsupported file type.
- Upload failed because file too large.
- Upload failed because parsed text is empty.
- Upload disabled while workflow is running.

## 14. Storage And Persistence

### 14.1 First Version

Store extracted text inside `AnalysisRun.run_payload` through `EvidenceSource.rawText`, same as other evidence.

Pros:

- Minimal schema change.
- Existing repository behavior works.
- Existing detail projection tables continue to refresh.

Cons:

- Large documents increase `run_payload` size.
- Large raw text may make history runs heavy.

### 14.2 File Binary Storage

Do not store uploaded binary files in the first version unless needed.

The first version should store:

- Original filename.
- Extracted text.
- Metadata.
- Generated internal document URL.

If binary retention becomes necessary later, add a separate table:

```text
uploaded_document
  id uuid primary key
  run_id uuid references analysis_run(id)
  original_filename text
  media_type text
  size_bytes bigint
  content_hash text
  storage_path text or bytea
  created_at timestamptz
```

But this is not required for RAG ingestion itself.

## 15. Limits And Validation

Recommended first-version limits:

```text
max file size: 10 MB
max extracted text length per document: 200,000 characters
max documents per run: 10
supported extensions: txt, md, pdf, docx
```

Validation rules:

- Reject empty files.
- Reject unsupported extensions/media types.
- Reject files above size limit.
- Reject extracted text shorter than a useful threshold, for example 80 characters.
- Truncate extremely long extracted text with a clear recommended action.

Example recommended action:

```text
文档内容较长，已截取前 200000 字用于本次分析。建议拆分上传关键章节以提升召回质量。
```

## 16. Security And Compliance

Uploaded documents may contain sensitive information. The implementation should:

- Never log raw document text.
- Never log full file content.
- Avoid returning binary content to the frontend.
- Mark sensitive documents as `INTERNAL_ONLY`.
- Add compliance notes to evidence sources.
- Avoid treating internal documents as public proof.
- Keep API keys and external services out of document logs.

If embeddings are configured through an external provider:

- Sensitive documents may be sent to the embedding provider unless explicitly blocked.
- Add a configuration option before production:

```text
AI_INSIGHT_EMBED_INTERNAL_DOCUMENTS=false
```

First-version option:

- If `sensitive=true`, skip external embedding and keep keyword retrieval only.
- If embedding provider is trusted in the deployment environment, this behavior can be changed by config.

This is important because embedding is also data egress.

## 17. Error Handling

Common errors:

| Scenario | Backend behavior | Frontend message |
| --- | --- | --- |
| Unsupported file type | 400 | 当前仅支持 txt、md、pdf、docx |
| File too large | 400 | 文件过大，请拆分后上传 |
| Empty extracted text | 400 | 未能从文档中提取有效文本 |
| Workflow running | 409 | 工作流运行中，暂不能上传文档 |
| Parser exception | 500 or 400 | 文档解析失败，请检查格式 |
| Embedding failure | Do not fail upload | 已加入证据链，语义检索暂不可用 |

Embedding failure should not fail ingestion. The document can still be used by keyword retrieval.

## 18. Testing Plan

### 18.1 Unit Tests

Add tests for:

- `DocumentTextExtractor`
  - TXT extraction.
  - Markdown extraction.
  - Unsupported extension rejection.
  - Empty text rejection.
- `DocumentIngestionService`
  - Creates `EvidenceSource`.
  - Allocates stable citation key.
  - Creates chunks.
  - Calls embedding service when available.
  - Skips or handles embeddings for sensitive documents.
  - Adds recommended rerun action.
- `AnalysisWorkflowService`
  - Upload allowed statuses.
  - Upload rejected statuses.
  - Repository save called.

### 18.2 Integration Tests

Add controller test:

```text
POST /api/analysis-runs/{runId}/documents
```

Verify:

- Multipart upload works.
- Response includes new evidence source.
- Evidence source appears in `researchPackage.sources`.
- Retrieval endpoint can find uploaded text.

### 18.3 Frontend Tests Or Build Verification

At minimum:

```powershell
cd frontend
npm run build
```

Manual checks:

- Upload form renders.
- Upload button disabled while no file selected.
- Upload result appears in EvidencePanel.
- Sensitive badge appears.
- Existing evidence text input still works.

### 18.4 Full Verification Commands

```powershell
mvn test
cd frontend
npm run build
```

## 19. Implementation Phases

### Phase 1: Backend Ingestion MVP

Files likely involved:

- `pom.xml`
- `AnalysisRunController`
- `AnalysisWorkflowService`
- New `DocumentIngestionService`
- New `DocumentTextExtractor`
- Tests under `src/test/java/com/aiinsight/service`
- Controller tests if existing style supports them

Acceptance criteria:

- Upload txt/md document.
- Evidence source and chunks are created.
- Retrieval can find uploaded text.
- `mvn test` passes.

### Phase 2: PDF/DOCX Support

Files likely involved:

- `pom.xml`
- `DocumentTextExtractor`
- Parser-specific tests with small fixture files.

Acceptance criteria:

- PDF text can be extracted.
- DOCX text can be extracted.
- Unsupported or empty documents fail with clear errors.

### Phase 3: Frontend Upload Experience

Files likely involved:

- `frontend/src/api.ts`
- `frontend/src/types.ts`
- `frontend/src/App.tsx`
- `frontend/src/components/ContextPanel.tsx` or new `DocumentUploadPanel.tsx`
- `frontend/src/components/EvidencePanel.tsx`
- `frontend/src/styles.css`

Acceptance criteria:

- User can upload a document from the workbench.
- New citation source appears immediately.
- Sensitive/internal-only state is visible.
- `npm run build` passes.

### Phase 4: Agent Prompt And Reviewer Policy Improvements

Files likely involved:

- `ResearcherNode`
- `ExtractorNode`
- `AnalystNode`
- `WriterNode`
- `ReviewerNode`
- Existing node tests

Acceptance criteria:

- Writer distinguishes user-provided/internal evidence from public evidence.
- Reviewer warns when a public-market claim relies only on internal evidence.
- Extractor/Analyst can cite uploaded document chunks.

### Phase 5: Document Metadata And Better Chunking

Files likely involved:

- `EvidenceChunk`
- `EvidenceChunkService`
- `PostgresAnalysisRunRepository`
- `EvidencePanel`

Acceptance criteria:

- Chunks preserve section/page metadata where available.
- Evidence panel can show document chunk context.
- Reviewer findings can later point to document sections.

## 20. Suggested First Code Shape

### 20.1 DocumentIngestionService Pseudocode

```java
public AnalysisRun ingest(AnalysisRun run,
                          MultipartFile file,
                          String title,
                          String sourceType,
                          boolean sensitive,
                          String notes) {
    validate(file);
    ExtractedDocumentText extracted = extractor.extract(file);

    String citationKey = nextCitationKey(run);
    EvidenceSource source = buildSource(citationKey, extracted, title, sourceType, sensitive, notes);

    run.getEvidenceSources().add(source);
    List<EvidenceChunk> chunks = evidenceChunkService.chunk(List.of(source));
    run.getEvidenceChunks().addAll(evidenceEmbeddingService.embedChunks(chunks));
    run.getResearchPackage().setSources(new ArrayList<>(run.getEvidenceSources()));
    run.getResearchPackage().setCollectedAt(Instant.now());
    run.getRecommendedActions().add("用户文档 " + citationKey + " 已加入证据链，可重跑 RESEARCHER 或 EXTRACTOR 刷新分析。");

    return run;
}
```

### 20.2 Frontend API Pseudocode

```ts
export async function uploadDocument(runId: string, payload: UploadDocumentRequest): Promise<AnalysisRun> {
  const body = new FormData();
  body.append("file", payload.file);
  if (payload.title) body.append("title", payload.title);
  if (payload.sourceType) body.append("sourceType", payload.sourceType);
  body.append("sensitive", String(Boolean(payload.sensitive)));
  if (payload.notes) body.append("notes", payload.notes);

  return requestJson(`/api/analysis-runs/${runId}/documents`, {
    method: "POST",
    body
  });
}
```

## 21. Acceptance Criteria For The Feature

The feature can be considered complete when:

- A user can upload at least TXT/Markdown/PDF/DOCX from the workbench.
- The backend creates a citation-bearing `EvidenceSource`.
- The backend creates `EvidenceChunk` entries from the uploaded document.
- Uploaded chunks participate in existing retrieval.
- The evidence appears in EvidencePanel.
- Sensitive documents are visibly marked and carry internal-only metadata.
- Existing user text evidence and URL collection continue to work.
- Manual rerun after upload can refresh downstream outputs.
- `mvn test` and `npm run build` pass.

## 22. Recommended First Commit Scope

Keep the first implementation small:

1. Add backend upload endpoint.
2. Support txt and md first.
3. Add `DocumentIngestionService`.
4. Reuse existing chunking and embedding.
5. Add tests for ingestion and retrieval.

Then add PDF/DOCX and frontend upload controls in separate commits. This reduces risk and makes each step easier to verify.

