import type { AttributeSpec, AttributeTier, AttributeType } from 'app/inspecto/component-model';

/**
 * The parser-type catalog that drives the {@link ParserConfigDialog} — one entry per supported file format
 * plus the typed property schema each exposes. Properties render through the shared
 * `<inspecto-schema-form>` (required tier up front, optional collapsed, advanced behind the gear — see
 * {@link toAttributeSpecs}); the `module` control (ASN.1 schema picker) is the one bespoke exception,
 * rendered by the dialog itself. Values persist as a reusable `grammar` component's content map
 * (`{ parser_type, ...props }`).
 *
 * <p>DSV mirrors the product spec + the backend {@code PipelineConfig.CsvSettings} fields exactly. The other
 * eight formats carry **best-guess** property sets (the spec only detailed DSV) — adjust as each parser's real
 * backend config firms up. {@link ParserTypeDef.hierarchical} types (ASN.1 / JSON / XML) preview their parse
 * output as a collapsible tree; the rest preview as a flat table.
 *
 * <p>**Tier audit (2026-07-02, review R2):** each property is classified `required` (defines whether the
 * format parses at all), `optional` (commonly adjusted but has a working default), or `advanced` (rarely
 * touched tuning knob) — see `docs/superpower/reviews/parser-config.md` for the rationale per type.
 */

/**
 * The editor control rendered for one property. `module` is the ASN.1 schema-module picker: a dropdown of
 * the server-side module library (its source is downloaded + shown read-only) plus a local-file upload —
 * excluded from {@link toAttributeSpecs} and rendered bespoke by the dialog.
 */
export type ParserPropControl = 'text' | 'number' | 'checkbox' | 'select' | 'textarea' | 'module';

/** One configurable property of a parser type — a single typed form field. */
export interface ParserProp {
    /** Content-map key this property persists under (snake_case, mirrors the grammar `.toon`). */
    key: string;
    label: string;
    control: ParserPropControl;
    /** Initial value when the type is first selected (also the reset value). */
    default: string | number | boolean;
    /** Disclosure tier — drives the shared schema-form's required/optional/advanced grouping. */
    tier: AttributeTier;
    /** Options for a `select` control. */
    options?: string[];
    /** Optional helper text under the field. */
    hint?: string;
    /** Show this property only while another property in the same type holds a given value. */
    dependsOn?: { key: string; equals: unknown };
}

/** A supported parser/file format + its property schema. */
export interface ParserTypeDef {
    /** Stable id stored as `parser_type` in the grammar content. */
    type: string;
    /** Dropdown label, "CODE — description" per the spec. */
    label: string;
    /** Hierarchical formats preview as a tree; flat formats preview as a table. */
    hierarchical: boolean;
    /** Format-specific properties (the shared Sampling knobs are appended by {@link propsFor}). */
    props: ParserProp[];
}

/** Sampling controls common to every parser type — tuning knobs, always advanced. */
const SAMPLING: ParserProp[] = [
    { key: 'sample_rows', label: 'Sample rows count', control: 'number', default: 100, tier: 'advanced' },
    { key: 'default_column_length', label: 'Default column length', control: 'number', default: 50, tier: 'advanced' },
    { key: 'count_length_in_bytes', label: 'Count length in bytes', control: 'checkbox', default: false, tier: 'advanced' },
];

/** The parser catalog, in the spec's dropdown order. */
export const PARSER_TYPES: ParserTypeDef[] = [
    {
        type: 'asn1',
        label: 'ASN.1 — ASN.1 file format',
        hierarchical: true,
        props: [
            { key: 'encoding_rules', label: 'Encoding rules', control: 'select', default: 'BER', tier: 'required', options: ['BER', 'DER', 'CER', 'PER', 'UPER'] },
            { key: 'schema_spec', label: 'ASN.1 schema (module)', control: 'module', default: '', tier: 'required', hint: 'Pick a module from the library or upload a .asn / .asn1 file' },
            { key: 'extension', label: 'Extension', control: 'text', default: 'ber,der,asn1', tier: 'optional' },
            { key: 'decode_implicit', label: 'Decode implicit tags', control: 'checkbox', default: true, tier: 'advanced' },
        ],
    },
    {
        type: 'dsv',
        label: 'DSV — Delimiter separated value files',
        hierarchical: false,
        props: [
            { key: 'column_delimiter', label: 'Column delimiter', control: 'text', default: ',', tier: 'required' },
            { key: 'header_position', label: 'Header position', control: 'select', default: 'top', tier: 'required', options: ['top', 'none', 'bottom'] },
            { key: 'extension', label: 'Extension', control: 'text', default: 'csv,tsv,txt', tier: 'optional' },
            { key: 'encoding', label: 'Encoding', control: 'text', default: 'utf-8', tier: 'optional' },
            { key: 'quote_char', label: 'Quote char', control: 'text', default: '"', tier: 'optional' },
            { key: 'null_value_mark', label: 'NULL value mark', control: 'text', default: '', tier: 'optional' },
            { key: 'datetime_format', label: 'Date/time format', control: 'text', default: 'yyyy-MM-dd[ HH:mm:ss[.SSS]]', tier: 'optional' },
            { key: 'ignore_chars_outside_quotes', label: 'Ignore characters outside quotes', control: 'checkbox', default: false, tier: 'advanced' },
            { key: 'escape_char', label: 'Escape char', control: 'text', default: '\\', tier: 'advanced' },
            { key: 'empty_string_as_null', label: 'Set empty strings to NULL', control: 'checkbox', default: false, tier: 'advanced' },
            { key: 'trim_whitespace', label: 'Trim whitespaces', control: 'checkbox', default: false, tier: 'advanced' },
            { key: 'timezone_id', label: 'Timezone ID', control: 'text', default: '', tier: 'advanced' },
        ],
    },
    {
        type: 'html',
        label: 'HTML — HTML file format',
        hierarchical: false,
        props: [
            { key: 'table_selector', label: 'Table selector', control: 'text', default: 'table', tier: 'required', hint: 'CSS selector or 0-based table index' },
            { key: 'header_position', label: 'Header position', control: 'select', default: 'top', tier: 'required', options: ['top', 'none'] },
            { key: 'extension', label: 'Extension', control: 'text', default: 'html,htm', tier: 'optional' },
            { key: 'encoding', label: 'Encoding', control: 'text', default: 'utf-8', tier: 'optional' },
            { key: 'datetime_format', label: 'Date/time format', control: 'text', default: 'yyyy-MM-dd', tier: 'optional' },
        ],
    },
    {
        type: 'json',
        label: 'JSON — JSON file format',
        hierarchical: true,
        props: [
            { key: 'mode', label: 'Document mode', control: 'select', default: 'array', tier: 'required', options: ['array', 'object', 'ndjson'] },
            { key: 'root_path', label: 'Record path', control: 'text', default: '$', tier: 'required', hint: 'JSONPath to the array/object of records' },
            { key: 'extension', label: 'Extension', control: 'text', default: 'json,jsonl,ndjson', tier: 'optional' },
            { key: 'encoding', label: 'Encoding', control: 'text', default: 'utf-8', tier: 'optional' },
        ],
    },
    {
        type: 'other',
        label: 'Other — Custom proprietary file format',
        hierarchical: false,
        props: [
            { key: 'plugin_class', label: 'Parser plugin (FQCN)', control: 'text', default: '', tier: 'required', hint: 'Fully-qualified class of the custom record parser' },
            { key: 'extension', label: 'Extension', control: 'text', default: '', tier: 'optional' },
            { key: 'plugin_config', label: 'Plugin config (JSON)', control: 'textarea', default: '{}', tier: 'advanced' },
        ],
    },
    {
        type: 'parquet',
        label: 'Parquet — Parquet format',
        hierarchical: false,
        props: [
            { key: 'extension', label: 'Extension', control: 'text', default: 'parquet', tier: 'optional' },
            { key: 'columns', label: 'Columns (projection)', control: 'text', default: '', tier: 'optional', hint: 'Comma-separated columns to read; blank = all' },
        ],
    },
    {
        type: 'txt',
        label: 'TXT — plain text format',
        hierarchical: false,
        props: [
            { key: 'frontend', label: 'Record frontend', control: 'select', default: 'line', tier: 'required', options: ['line', 'fixedwidth'] },
            {
                key: 'record_length', label: 'Record length', control: 'number', default: 0, tier: 'required',
                dependsOn: { key: 'frontend', equals: 'fixedwidth' }, hint: 'Fixed-width record length',
            },
            { key: 'extension', label: 'Extension', control: 'text', default: 'txt,log', tier: 'optional' },
            { key: 'encoding', label: 'Encoding', control: 'text', default: 'utf-8', tier: 'optional' },
            { key: 'datetime_format', label: 'Date/time format', control: 'text', default: 'yyyy-MM-dd HH:mm:ss', tier: 'optional' },
            { key: 'trim_whitespace', label: 'Trim whitespaces', control: 'checkbox', default: true, tier: 'advanced' },
            { key: 'timezone_id', label: 'Timezone ID', control: 'text', default: '', tier: 'advanced' },
        ],
    },
    {
        type: 'xlsx',
        label: 'XLSX — XLSX (Excel spreadsheet) format',
        hierarchical: false,
        props: [
            { key: 'header_position', label: 'Header position', control: 'select', default: 'top', tier: 'required', options: ['top', 'none'] },
            { key: 'sheet', label: 'Sheet', control: 'text', default: '', tier: 'optional', hint: 'Sheet name or index; blank = first sheet' },
            { key: 'extension', label: 'Extension', control: 'text', default: 'xlsx,xls', tier: 'optional' },
            { key: 'datetime_format', label: 'Date/time format', control: 'text', default: 'yyyy-MM-dd', tier: 'optional' },
            { key: 'skip_rows', label: 'Skip leading rows', control: 'number', default: 0, tier: 'advanced' },
        ],
    },
    {
        type: 'xml',
        label: 'XML — XML file format',
        hierarchical: true,
        props: [
            { key: 'record_xpath', label: 'Record element (XPath)', control: 'text', default: '/*/*', tier: 'required', hint: 'XPath selecting each record element' },
            { key: 'extension', label: 'Extension', control: 'text', default: 'xml', tier: 'optional' },
            { key: 'encoding', label: 'Encoding', control: 'text', default: 'utf-8', tier: 'optional' },
            { key: 'namespace_aware', label: 'Namespace aware', control: 'checkbox', default: false, tier: 'advanced' },
        ],
    },
];

const BY_TYPE = new Map(PARSER_TYPES.map((t) => [t.type, t]));

/** Look up a parser type def by id (defaults to DSV for an unknown/blank type). */
export function parserTypeDef(type: string | undefined): ParserTypeDef {
    return (type && BY_TYPE.get(type)) || BY_TYPE.get('dsv')!;
}

/** A type's own properties followed by the shared Sampling knobs — the full ordered field list. */
export function propsFor(type: string | undefined): ParserProp[] {
    return [...parserTypeDef(type).props, ...SAMPLING];
}

const CONTROL_TO_ATTRIBUTE_TYPE: Partial<Record<ParserPropControl, AttributeType>> = {
    text: 'string',
    number: 'number',
    checkbox: 'boolean',
    select: 'select',
    textarea: 'multiline',
};

/**
 * The non-`module` properties of a type as {@link AttributeSpec}s for `<inspecto-schema-form>`. The
 * `module` control (ASN.1 schema picker) is excluded — it needs a network-backed picker + upload, so the
 * dialog renders it bespoke; see {@link modulePropFor}.
 */
export function toAttributeSpecs(type: string | undefined): AttributeSpec[] {
    return propsFor(type)
        .filter((p) => p.control !== 'module')
        .map((p) => ({
            key: p.key,
            label: p.label,
            type: CONTROL_TO_ATTRIBUTE_TYPE[p.control] ?? 'string',
            tier: p.tier,
            default: p.default,
            options: p.options?.map((o) => ({ value: o, label: o })),
            hint: p.hint,
            dependsOn: p.dependsOn,
        }));
}

/** The type's `module`-control property (the ASN.1 schema picker), if it has one. */
export function modulePropFor(type: string | undefined): ParserProp | undefined {
    return parserTypeDef(type).props.find((p) => p.control === 'module');
}

/** A short illustrative sample seeded into the content viewer so Test has something to chew on. */
export function sampleFor(type: string | undefined): string {
    switch (parserTypeDef(type).type) {
        case 'dsv':
            return 'id,msisdn,start_time,duration_s\n1001,8801700000001,2026-06-24 09:00:00,42\n1002,8801700000002,2026-06-24 09:01:30,17';
        case 'json':
            return '[\n  { "id": 1001, "msisdn": "8801700000001", "duration_s": 42 },\n  { "id": 1002, "msisdn": "8801700000002", "duration_s": 17 }\n]';
        case 'xml':
            return '<records>\n  <record id="1001"><msisdn>8801700000001</msisdn><duration_s>42</duration_s></record>\n  <record id="1002"><msisdn>8801700000002</msisdn><duration_s>17</duration_s></record>\n</records>';
        case 'asn1':
            return '# BER-encoded sample (hex)\n30 0E 02 02 03 E9 04 0B 38 38 30 31 37 30 30 30 30 30 31';
        case 'txt':
            return '1001 8801700000001 0042\n1002 8801700000002 0017';
        default:
            return '';
    }
}
