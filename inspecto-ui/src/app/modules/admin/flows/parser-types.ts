/**
 * The parser-type catalog that drives the {@link ParserConfigDialog} — one entry per supported file format
 * plus the typed property schema each exposes. The dialog renders these into grouped Material form fields
 * (the "typed form" surface) and persists the values as a reusable `grammar` component's content map
 * (`{ parser_type, ...props }`).
 *
 * <p>DSV mirrors the product spec + the backend {@code PipelineConfig.CsvSettings} fields exactly. The other
 * eight formats carry **best-guess** property sets (the spec only detailed DSV) — adjust as each parser's real
 * backend config firms up. {@link ParserTypeDef.hierarchical} types (ASN.1 / JSON / XML) preview their parse
 * output as a collapsible tree; the rest preview as a flat table.
 */

/**
 * The editor control rendered for one property. `module` is the ASN.1 schema-module picker: a dropdown of
 * the server-side module library (its source is downloaded + shown read-only) plus a local-file upload.
 */
export type ParserPropControl = 'text' | 'number' | 'checkbox' | 'select' | 'textarea' | 'module';

/** One configurable property of a parser type — a single typed form field, grouped under a {@link section}. */
export interface ParserProp {
    /** Content-map key this property persists under (snake_case, mirrors the grammar `.toon`). */
    key: string;
    label: string;
    control: ParserPropControl;
    /** Initial value when the type is first selected (also the reset value). */
    default: string | number | boolean;
    /** Section header the field renders under (e.g. "Properties" / "Sampling"). */
    section: string;
    /** Options for a `select` control. */
    options?: string[];
    /** Optional helper text under the field. */
    hint?: string;
}

/** A supported parser/file format + its property schema. */
export interface ParserTypeDef {
    /** Stable id stored as `parser_type` in the grammar content. */
    type: string;
    /** Dropdown label, "CODE — description" per the spec. */
    label: string;
    /** Hierarchical formats preview as a tree; flat formats preview as a table. */
    hierarchical: boolean;
    /** Format-specific properties (the shared Sampling section is appended by {@link propsFor}). */
    props: ParserProp[];
}

/** Sampling controls common to every parser type (appended after each type's own properties). */
const SAMPLING: ParserProp[] = [
    { key: 'sample_rows', label: 'Sample rows count', control: 'number', default: 100, section: 'Sampling' },
    { key: 'default_column_length', label: 'Default column length', control: 'number', default: 50, section: 'Sampling' },
    { key: 'count_length_in_bytes', label: 'Count length in bytes', control: 'checkbox', default: false, section: 'Sampling' },
];

/** The parser catalog, in the spec's dropdown order. */
export const PARSER_TYPES: ParserTypeDef[] = [
    {
        type: 'asn1',
        label: 'ASN.1 — ASN.1 file format',
        hierarchical: true,
        props: [
            { key: 'extension', label: 'Extension', control: 'text', default: 'ber,der,asn1', section: 'Properties' },
            { key: 'encoding_rules', label: 'Encoding rules', control: 'select', default: 'BER', section: 'Properties', options: ['BER', 'DER', 'CER', 'PER', 'UPER'] },
            { key: 'schema_spec', label: 'ASN.1 schema (module)', control: 'module', default: '', section: 'Properties', hint: 'Pick a module from the library or upload a .asn / .asn1 file' },
            { key: 'decode_implicit', label: 'Decode implicit tags', control: 'checkbox', default: true, section: 'Properties' },
        ],
    },
    {
        type: 'dsv',
        label: 'DSV — Delimiter separated value files',
        hierarchical: false,
        props: [
            { key: 'extension', label: 'Extension', control: 'text', default: 'csv,tsv,txt', section: 'Properties' },
            { key: 'encoding', label: 'Encoding', control: 'text', default: 'utf-8', section: 'Properties' },
            { key: 'column_delimiter', label: 'Column delimiter', control: 'text', default: ',', section: 'Properties' },
            { key: 'header_position', label: 'Header position', control: 'select', default: 'top', section: 'Properties', options: ['top', 'none', 'bottom'] },
            { key: 'quote_char', label: 'Quote char', control: 'text', default: '"', section: 'Properties' },
            { key: 'ignore_chars_outside_quotes', label: 'Ignore characters outside quotes', control: 'checkbox', default: false, section: 'Properties' },
            { key: 'escape_char', label: 'Escape char', control: 'text', default: '\\', section: 'Properties' },
            { key: 'null_value_mark', label: 'NULL value mark', control: 'text', default: '', section: 'Properties' },
            { key: 'empty_string_as_null', label: 'Set empty strings to NULL', control: 'checkbox', default: false, section: 'Properties' },
            { key: 'datetime_format', label: 'Date/time format', control: 'text', default: 'yyyy-MM-dd[ HH:mm:ss[.SSS]]', section: 'Properties' },
            { key: 'trim_whitespace', label: 'Trim whitespaces', control: 'checkbox', default: false, section: 'Properties' },
            { key: 'timezone_id', label: 'Timezone ID', control: 'text', default: '', section: 'Properties' },
        ],
    },
    {
        type: 'html',
        label: 'HTML — HTML file format',
        hierarchical: false,
        props: [
            { key: 'extension', label: 'Extension', control: 'text', default: 'html,htm', section: 'Properties' },
            { key: 'encoding', label: 'Encoding', control: 'text', default: 'utf-8', section: 'Properties' },
            { key: 'table_selector', label: 'Table selector', control: 'text', default: 'table', section: 'Properties', hint: 'CSS selector or 0-based table index' },
            { key: 'header_position', label: 'Header position', control: 'select', default: 'top', section: 'Properties', options: ['top', 'none'] },
            { key: 'datetime_format', label: 'Date/time format', control: 'text', default: 'yyyy-MM-dd', section: 'Properties' },
        ],
    },
    {
        type: 'json',
        label: 'JSON — JSON file format',
        hierarchical: true,
        props: [
            { key: 'extension', label: 'Extension', control: 'text', default: 'json,jsonl,ndjson', section: 'Properties' },
            { key: 'encoding', label: 'Encoding', control: 'text', default: 'utf-8', section: 'Properties' },
            { key: 'mode', label: 'Document mode', control: 'select', default: 'array', section: 'Properties', options: ['array', 'object', 'ndjson'] },
            { key: 'root_path', label: 'Record path', control: 'text', default: '$', section: 'Properties', hint: 'JSONPath to the array/object of records' },
        ],
    },
    {
        type: 'other',
        label: 'Other — Custom proprietary file format',
        hierarchical: false,
        props: [
            { key: 'extension', label: 'Extension', control: 'text', default: '', section: 'Properties' },
            { key: 'plugin_class', label: 'Parser plugin (FQCN)', control: 'text', default: '', section: 'Properties', hint: 'Fully-qualified class of the custom record parser' },
            { key: 'plugin_config', label: 'Plugin config (JSON)', control: 'textarea', default: '{}', section: 'Properties' },
        ],
    },
    {
        type: 'parquet',
        label: 'Parquet — Parquet format',
        hierarchical: false,
        props: [
            { key: 'extension', label: 'Extension', control: 'text', default: 'parquet', section: 'Properties' },
            { key: 'columns', label: 'Columns (projection)', control: 'text', default: '', section: 'Properties', hint: 'Comma-separated columns to read; blank = all' },
        ],
    },
    {
        type: 'txt',
        label: 'TXT — plain text format',
        hierarchical: false,
        props: [
            { key: 'extension', label: 'Extension', control: 'text', default: 'txt,log', section: 'Properties' },
            { key: 'encoding', label: 'Encoding', control: 'text', default: 'utf-8', section: 'Properties' },
            { key: 'frontend', label: 'Record frontend', control: 'select', default: 'line', section: 'Properties', options: ['line', 'fixedwidth'] },
            { key: 'record_length', label: 'Record length', control: 'number', default: 0, section: 'Properties', hint: 'Fixed-width record length (0 = line mode)' },
            { key: 'trim_whitespace', label: 'Trim whitespaces', control: 'checkbox', default: true, section: 'Properties' },
            { key: 'datetime_format', label: 'Date/time format', control: 'text', default: 'yyyy-MM-dd HH:mm:ss', section: 'Properties' },
            { key: 'timezone_id', label: 'Timezone ID', control: 'text', default: '', section: 'Properties' },
        ],
    },
    {
        type: 'xlsx',
        label: 'XLSX — XLSX (Excel spreadsheet) format',
        hierarchical: false,
        props: [
            { key: 'extension', label: 'Extension', control: 'text', default: 'xlsx,xls', section: 'Properties' },
            { key: 'sheet', label: 'Sheet', control: 'text', default: '', section: 'Properties', hint: 'Sheet name or index; blank = first sheet' },
            { key: 'header_position', label: 'Header position', control: 'select', default: 'top', section: 'Properties', options: ['top', 'none'] },
            { key: 'skip_rows', label: 'Skip leading rows', control: 'number', default: 0, section: 'Properties' },
            { key: 'datetime_format', label: 'Date/time format', control: 'text', default: 'yyyy-MM-dd', section: 'Properties' },
        ],
    },
    {
        type: 'xml',
        label: 'XML — XML file format',
        hierarchical: true,
        props: [
            { key: 'extension', label: 'Extension', control: 'text', default: 'xml', section: 'Properties' },
            { key: 'encoding', label: 'Encoding', control: 'text', default: 'utf-8', section: 'Properties' },
            { key: 'record_xpath', label: 'Record element (XPath)', control: 'text', default: '/*/*', section: 'Properties', hint: 'XPath selecting each record element' },
            { key: 'namespace_aware', label: 'Namespace aware', control: 'checkbox', default: false, section: 'Properties' },
        ],
    },
];

const BY_TYPE = new Map(PARSER_TYPES.map((t) => [t.type, t]));

/** Look up a parser type def by id (defaults to DSV for an unknown/blank type). */
export function parserTypeDef(type: string | undefined): ParserTypeDef {
    return (type && BY_TYPE.get(type)) || BY_TYPE.get('dsv')!;
}

/** A type's own properties followed by the shared Sampling section — the full ordered field list. */
export function propsFor(type: string | undefined): ParserProp[] {
    return [...parserTypeDef(type).props, ...SAMPLING];
}

/** Distinct section headers for a type, in first-seen order (drives the form's section grouping). */
export function sectionsFor(type: string | undefined): string[] {
    const seen: string[] = [];
    for (const p of propsFor(type)) if (!seen.includes(p.section)) seen.push(p.section);
    return seen;
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
