/**
 * The 3-layer incident categorization taxonomy (GLOSSARY §9): every Incident is categorized
 * L1 → L2 → L3 at creation, or at the latest when it is Accepted into Diagnosing. Stored on the
 * object as `attributes.category` = the three labels joined with {@link CATEGORY_SEPARATOR}.
 */
export const INCIDENT_TAXONOMY: Record<string, Record<string, string[]>> = {
    Infrastructure: {
        Compute: ['CPU saturation', 'Memory leak', 'Disk full'],
        Network: ['Connectivity', 'Latency', 'DNS'],
        Storage: ['Capacity', 'Corruption', 'Access denied'],
    },
    'Data Quality': {
        Completeness: ['Missing records', 'Null values', 'Truncated feed'],
        Accuracy: ['Value mismatch', 'Duplicate records', 'Schema drift'],
        Timeliness: ['Late arrival', 'Stale data', 'Out of order'],
    },
    Pipeline: {
        Ingest: ['Source unreachable', 'Parse failure', 'Checkpoint lost'],
        Transform: ['Job failure', 'Expectation breach', 'Resource limit'],
        Delivery: ['Sink unavailable', 'Partial write', 'Format error'],
    },
    Application: {
        API: ['Endpoint error', 'Timeout', 'Contract violation'],
        UI: ['Rendering defect', 'Broken workflow', 'Performance'],
        Job: ['Missed schedule', 'Hung run', 'Crash loop'],
    },
    Security: {
        Access: ['Unauthorized access', 'Privilege escalation', 'Expired credentials'],
        Data: ['Leak suspected', 'PII exposure', 'Tamper alert'],
    },
};

export const CATEGORY_SEPARATOR = ' / ';

/** Join the three levels into the stored `attributes.category` value. */
export function joinCategory(l1: string, l2: string, l3: string): string {
    return [l1, l2, l3].join(CATEGORY_SEPARATOR);
}

/** Split a stored category back into its (up to) three levels. */
export function splitCategory(category: string): string[] {
    return category ? category.split(CATEGORY_SEPARATOR).map((s) => s.trim()) : [];
}
