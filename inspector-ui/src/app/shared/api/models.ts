// TypeScript mirrors of the ControlApi JSON DTOs (see com.gamma.control.ControlApi and friends).
// Kept intentionally tolerant: many audit endpoints return List<Map<String,String>> which we model
// as AuditRow; optional fields are marked so minor backend variations don't break compilation.

/** A generic header→value audit row (batches/files/lineage/quarantine/commits, enrichment runs…). */
export type AuditRow = Record<string, string>;

// ── pipelines ────────────────────────────────────────────────────────────────
export interface PipelineView {
  name: string;
  configPath: string;
  paused: boolean;
  committedBatches: number;
}

/** Result of POST /pipelines/{n}/trigger and /trigger (MultiSourceProcessor.RunResult). */
export interface PipelineRunResult {
  total: number;
  failed: number;
  [k: string]: unknown;
}

/** Inbox/processing status (GET /pipelines/{n}/pending). */
export interface InboxStatus {
  pipeline: string;
  inbox: string;
  pending: number;   // files matched but not yet processed; -1 if the scan failed
  running: boolean;  // pipeline currently mid-ingest ("under processing")
}

// ── status + reports ───────────────────────────────────────────────────────────
export interface PipelineStatus {
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
  pipelines: PipelineStatus[];
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
export type NodeKind = 'SOURCE' | 'SCHEMA' | 'COLUMN' | 'TABLE' | 'KPI' | 'REPORT' | 'ENRICHMENT' | string;
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
export type FieldType = 'STRING' | 'INTEGER' | 'BOOLEAN' | 'ARRAY' | 'FILEPATH' | string;
export type Severity = 'ERROR' | 'WARNING' | string;

export interface FieldSpec {
  path: string;
  type: FieldType;
  required: boolean;
  description?: string;
  default?: unknown;
  options?: string[] | null;
  minValue?: number | null;
  maxValue?: number | null;
}

export interface CrossFieldRule {
  description: string;
  /** Field paths the rule reasons about (backend field name: {@code affectedPaths}). */
  affectedPaths: string[];
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

/** POST /config/write — persist a validated draft under the server's write root (v4.1). */
export interface WriteConfigResult {
  type: string;
  written: boolean;
  /** Root-relative path of the written .toon (also the configPath for POST /pipelines). */
  path: string;
  name: string;
  bytes: number;
  overwritten: boolean;
  findings: Finding[];
}

/** POST /pipelines — register a saved config as a live pipeline, no restart (v4.1). */
export interface RegisterPipelineResult {
  registered: boolean;
  id: string;
  path: string;
  pipeline?: PipelineView;
  findings: Finding[];
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

/** The seven assist skills exposed at POST /assist/{intent}. */
export const ASSIST_INTENTS = [
  'explain-entity',
  'nl-to-schedule',
  'suggest-config',
  'kpi-to-sql',
  'diagnose-and-alert',
  'report-sql',
  'report-narrative',
] as const;
export type AssistIntent = (typeof ASSIST_INTENTS)[number];

// ── health ─────────────────────────────────────────────────────────────────────
export interface ReadyStatus {
  status: 'READY' | 'INITIALIZING' | string;
  pipelines: number;
}
