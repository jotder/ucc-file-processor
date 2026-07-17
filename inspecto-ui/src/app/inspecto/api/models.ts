// TypeScript mirrors of the ControlApi JSON DTOs (see com.gamma.control.ControlApi and friends).
// Kept intentionally tolerant: many audit endpoints return List<Map<String,String>> which we model
// as AuditRow; optional fields are marked so minor backend variations don't break compilation.

/** A generic header→value audit row (batches/files/lineage/quarantine/commits, enrichment runs…). */
export type AuditRow = Record<string, string>;

// ── runs ─────────────────────────────────────────────────────────────────────
export interface RunView {
  name: string;
  configPath: string;
  paused: boolean;
  committedBatches: number;
}

/** Result of POST /trigger — run-all across pipelines (MultiSourceProcessor.RunResult). Per-pipeline
 *  /runs/{n}/trigger is async (W5b): it returns {runId}, and the terminal RunResult counts arrive via poll. */
export interface RunResult {
  total: number;
  failed: number;
  [k: string]: unknown;
}

/** The file a pipeline is ingesting right now ("file index of total" within a batch). */
export interface IngestSnapshot {
  batchId: string;
  file: string;
  index: number;     // 1-based position within the batch
  total: number;     // batch member count
  startedAt: string;
}

/** Inbox/processing status (GET /runs/{n}/pending). */
export interface InboxStatus {
  pipeline: string;
  inbox: string;
  pending: number;   // files matched but not yet processed; -1 if the scan failed
  running: boolean;  // pipeline currently mid-ingest ("under processing")
  current?: IngestSnapshot | null; // live per-file progress; absent when not mid-file
}

// ── status + reports ───────────────────────────────────────────────────────────
export interface RunStatus {
  pipeline: string;
  paused: boolean;
  committedBatches: number;
  quarantineFiles: number;
  lastBatchId?: string;
  lastBatchStatus?: string;
  lastBatchTime?: string;
}

export interface StatusReport {
  generatedAt: string;
  pipelineCount: number;
  pausedCount: number;
  totalCommittedBatches: number;
  totalQuarantineFiles: number;
  pipelines: RunStatus[];
}

export interface BatchAuditReport {
  pipeline: string;
  totalBatches: number;
  success: number;
  failed: number;
  errorRate: number;
  totalInputRows?: number;
  totalOutputRows: number;
  totalRejectedFiles?: number;
  totalOutputFiles?: number;
  totalOutputBytes?: number;
  avgDurationMs?: number;
  maxDurationMs?: number;
  p50DurationMs: number;
  p95DurationMs: number;
  p99DurationMs: number;
  firstBatchTime?: string;
  lastBatchTime?: string;
  windowFrom: string;
  windowTo: string;
}

export interface ServiceReport {
  generatedAt: string;
  totalBatches: number;
  success: number;
  failed: number;
  errorRate: number;
  totalOutputRows: number;
  p50DurationMs: number;
  p95DurationMs: number;
  p99DurationMs: number;
  windowFrom: string;
  windowTo: string;
  pipelines: BatchAuditReport[];
}

/** Inclusive report window; blank strings mean "unbounded". */
export interface ReportWindow {
  from?: string;
  to?: string;
}

// ── jobs ─────────────────────────────────────────────────────────────────────
export type JobType = 'ingest' | 'enrich' | 'report' | 'maintenance' | string;
export type JobRunStatus = 'PENDING' | 'RUNNING' | 'SKIPPED' | 'SUCCESS' | 'FAILED' | string;
export type TriggerType = 'CRON' | 'EVENT' | 'MANUAL' | string;

export interface JobView {
  name: string;
  type: JobType;
  cron?: string | null;
  onPipeline?: string | null;
  enabled: boolean;
  lastStatus?: string;
  lastRunTime?: string;
  nextFire?: string;
}

export interface JobRun {
  jobName: string;
  runId: string;
  status: JobRunStatus;
  startTime?: string;
  endTime?: string;
  durationMs?: number;
  triggerType?: TriggerType;
  error?: string | null;
}

// ── enrichment ─────────────────────────────────────────────────────────────────
export interface EnrichmentJobView {
  name: string;
  onPipeline?: string;
  eventTriggered?: boolean;
  scheduleTriggered?: boolean;
  runCount?: number;
  lastRunId?: string | null;
  lastRunStatus?: string;
  lastRunTime?: string;
}

export interface EnrichmentRunReport {
  job: string;
  totalRuns: number;
  success: number;
  failed: number;
  errorRate: number;
  totalOutputRows: number;
  totalOutputFiles?: number;
  totalOutputBytes?: number;
  avgDurationMs?: number;
  maxDurationMs?: number;
  p50DurationMs: number;
  p95DurationMs: number;
  p99DurationMs: number;
  firstRunTime?: string;
  lastRunTime?: string;
  windowFrom: string;
  windowTo: string;
}

// ── catalog / metadata graph ─────────────────────────────────────────────────
export type NodeKind = 'STREAM' | 'SCHEMA' | 'COLUMN' | 'TABLE' | 'DERIVED_TABLE' | 'REFERENCE_DATASET' | 'KPI' | 'REPORT' | 'ENRICHMENT' | string;
export type EdgeKind = 'EMITS' | 'CONSUMES' | 'COMPUTED_FROM' | 'REFERENCES' | string;
export type Freshness = 'FRESH' | 'STALE' | 'MISSING' | string;

export interface NodeDescription {
  text: string;
  source?: string;
  confidence?: number;
}

export interface OperationalOverlay {
  lastSeen?: string | null;
  rowCount?: number | null;
  partitionCount?: number;
  freshness?: Freshness;
  completeness?: number;
}

export interface MetadataNode {
  id: string;
  kind: NodeKind;
  label: string;
  description?: NodeDescription;
  attrs?: Record<string, unknown>;
  overlay?: OperationalOverlay | null;
}

export interface MetadataEdge {
  from: string;
  to: string;
  kind: EdgeKind;
}

export interface MetadataGraph {
  nodes: MetadataNode[];
  edges: MetadataEdge[];
}

export interface NodeDetail {
  node: MetadataNode;
  neighbors: MetadataGraph;
}

export interface KpiCatalogEntry {
  id: string;
  name: string;
  definition?: string;
  grain?: string;
  joinKeys?: string[];
  inputs?: string[];
}

export interface KpiCatalog {
  kpis: KpiCatalogEntry[];
  domain?: string;
}

export type GraphDirection = 'out' | 'in' | 'both';

export interface GraphQuery {
  from?: string;
  depth?: number;
  direction?: GraphDirection;
  kinds?: string[];     // NodeKind csv
  edgeKinds?: string[]; // EdgeKind csv
  overlay?: boolean;
}

// ── config spec + validation ─────────────────────────────────────────────────
export type ConfigType = 'pipeline' | 'enrichment' | 'job' | 'schema' | 'meta';
/** Mirrors the backend `com.gamma.config.spec.FieldType` (FILEPATH/CRON/SQL are STRING refinements). */
export type FieldType =
  | 'STRING' | 'INT' | 'LONG' | 'BOOL' | 'ENUM' | 'FILEPATH' | 'CRON' | 'SQL' | 'MAP' | 'LIST'
  | string;
export type Severity = 'ERROR' | 'WARNING' | string;

/** Mirrors the backend `com.gamma.config.spec.FieldSpec` record (GET /config/spec/{type}). */
export interface FieldSpec {
  path: string;
  /** Short human label for the form control (backend guarantees non-null; may be blank). */
  label?: string;
  type: FieldType;
  required: boolean;
  description?: string;
  defaultValue?: unknown;
  /** Allowed values when `type` is ENUM. */
  enumValues?: string[] | null;
  /** Regex the value must fully match. */
  pattern?: string | null;
  /** Optional rendering hint (e.g. "select", "cron-editor") — advisory only. */
  uiHint?: string | null;
  /** Optional "otherPath=value" display predicate — advisory only, unused by current specs. */
  visibleWhen?: string | null;
}

export interface CrossFieldRule {
  description: string;
  affectedFields: string[];
  condition: string;
}

export interface ConfigSpec {
  type: string;
  fields: FieldSpec[];
  rules: CrossFieldRule[];
}

export interface Finding {
  severity: Severity;
  fieldPath: string;
  message: string;
}

export interface ValidateResult {
  pipeline?: string;
  type?: string;
  warnings?: string[];
  findings: Finding[];
  safetyChecked?: boolean;
  clean: boolean;
}

/** POST /config/write result. */
export interface ConfigWriteResult {
  type: string;
  written: boolean;
  /** Relative to the write root — feed it to {@link ConfigService.registerPipeline}. */
  path: string;
  name: string;
  bytes: number;
  overwritten: boolean;
  findings: Finding[];
}

/** GET /config/{type}/{name} result — a config read back as its decoded map (onboarding resume). */
export interface ConfigReadResult {
  type: string;
  name: string;
  path: string;
  config: Record<string, unknown>;
}

/** DELETE /config/{type}/{name} result (draft discard). */
export interface ConfigDeleteResult {
  type: string;
  name: string;
  deleted: boolean;
  path: string;
}

/** POST /runs result — a written pipeline file registered with the running service. */
export interface PipelineRegisterResult {
  registered: boolean;
  id: string;
  path: string;
  findings?: Finding[];
}

/** POST /enrichment result — a written enrichment hot-registered (or replaced by name). */
export interface EnrichmentRegisterResult {
  registered: boolean;
  name: string;
  path: string;
  findings?: Finding[];
}

/** POST /config/preview/parsing result — a raw sample parsed with a draft's parsing settings. */
export interface ParsingPreview {
  frontend: 'delimited' | 'fixedwidth' | 'json' | 'text_regex' | 'plugin' | string;
  columns: string[];
  rowCount: number;
  rows: Record<string, unknown>[];
  rejectedRows: number;
}

/** Result of POST /config/preview/schema — TRY_CAST already-parsed rows against typed fields. */
export interface SchemaPreview {
  columns: string[];
  okCount: number;
  rejectedCount: number;
  rejectedRows: Record<string, unknown>[];
}

// ── diagnoses + assist ───────────────────────────────────────────────────────
export interface Citation {
  source: string;
  ref: string;
}

export type DiagnosisSeverity = 'INFO' | 'WARNING' | 'CRITICAL' | string;

export interface Diagnosis {
  batchId: string;
  pipeline: string;
  severity: DiagnosisSeverity;
  rootCause: string;
  suggestedAlertRuleToon?: string | null;
  heuristicOnly: boolean;
  epochMillis: number;
  citations: Citation[];
}

export type AssistStatus = 'OK' | 'UNSUPPORTED' | 'UNAVAILABLE' | string;

export interface AssistRequest {
  screenContext?: Record<string, unknown>;
  partialInput?: Record<string, unknown>;
  userText?: string;
}

export interface AssistResult {
  intent: string;
  status: AssistStatus;
  answer: string;
  citations: Citation[];
  links: string[];
  rationale?: string | null;
  confidence: number;
  validated: boolean;
  applyVia?: string | null;
  message?: string | null;
  data: Record<string, unknown>;
}

/** The assist skills exposed at POST /assist/{intent}. `propose-decision` (R5) makes the Assist a
 *  **decision engine**: it returns proposed {@link Consequence}s in `data.consequences`, which a human
 *  reviews and saves as a Decision Rule (approval = the consequence gate). */
export const ASSIST_INTENTS = [
  'explain-entity',
  'nl-to-schedule',
  'suggest-config',
  'kpi-to-sql',
  'diagnose-and-alert',
  'report-sql',
  'report-narrative',
  'propose-decision',
] as const;
export type AssistIntent = (typeof ASSIST_INTENTS)[number];

// ── health ─────────────────────────────────────────────────────────────────────
export interface ReadyStatus {
  status: 'READY' | 'INITIALIZING' | string;
  pipelines: number;
}
