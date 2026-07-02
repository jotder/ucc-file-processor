import type { ConfigFinding } from './component-kind';

/**
 * The attribute registry (Wave 0, W2 — see docs/superpower/frontend-review-and-completion-plan.md).
 *
 * Every Component Type declares its config attributes as {@link AttributeSpec}s, classified into the
 * three disclosure tiers the product mandates: **required** (always visible, must be filled),
 * **optional** (visible but collapsed), **advanced** (hidden behind the advanced toggle). One shared
 * renderer (`<inspecto-schema-form>`) generates every form from these specs, so the per-pane
 * attribute audit (review step R2) fixes the spec once and every consumer inherits it.
 *
 * Framework-free — validation returns {@link ConfigFinding}s (never throws), like kind validators.
 */

export type AttributeTier = 'required' | 'optional' | 'advanced';

export type AttributeType =
    | 'string'
    | 'identifier' // machine name: letters/digits/_-, no spaces
    | 'number'
    | 'boolean'
    | 'select'
    | 'multiline';

export interface AttributeOption {
    value: string;
    label: string;
}

export interface AttributeSpec {
    key: string;
    label: string;
    type: AttributeType;
    /** Disclosure/visibility bucket: required = always visible, optional = collapsed, advanced = behind the gear. */
    tier: AttributeTier;
    /**
     * Whether the value must be filled. Defaults to `tier === 'required'`. Set explicitly to decouple
     * validation from visibility — e.g. an always-visible field that is optional (`tier: 'required',
     * required: false`), as used by option sheets where every knob shows but none is mandatory.
     */
    required?: boolean;
    default?: unknown;
    /** Choices for `type: 'select'`. */
    options?: AttributeOption[];
    /** Regex the (string) value must fully match. */
    pattern?: string;
    /** Bounds for `type: 'number'`. */
    min?: number;
    max?: number;
    /** Show this attribute only while another attribute holds a given value. */
    dependsOn?: { key: string; equals: unknown };
    /** One-line helper text shown under the field. */
    help?: string;
    placeholder?: string;
}

const IDENTIFIER_RE = /^[A-Za-z][A-Za-z0-9_-]*$/;

/** Whether a spec's value must be filled — explicit `required`, else derived from the `required` tier. */
export function isRequired(spec: AttributeSpec): boolean {
    return spec.required ?? spec.tier === 'required';
}

/** The declared defaults, for initialising a new instance's config. */
export function defaultsFor(specs: AttributeSpec[]): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const s of specs) {
        if (s.default !== undefined) out[s.key] = s.default;
    }
    return out;
}

/** The specs visible for `value`, honouring `dependsOn` (hidden attributes are also not validated). */
export function visibleSpecs(specs: AttributeSpec[], value: Record<string, unknown>): AttributeSpec[] {
    return specs.filter((s) => !s.dependsOn || value[s.dependsOn.key] === s.dependsOn.equals);
}

/** Group specs by tier, preserving declaration order. */
export function byTier(specs: AttributeSpec[]): Record<AttributeTier, AttributeSpec[]> {
    const out: Record<AttributeTier, AttributeSpec[]> = { required: [], optional: [], advanced: [] };
    for (const s of specs) out[s.tier].push(s);
    return out;
}

const isBlank = (v: unknown): boolean => v === undefined || v === null || v === '';

/** Validate `value` against the visible specs — the spec-driven half of a kind's `config.validate`. */
export function validateAttributes(specs: AttributeSpec[], value: Record<string, unknown>): ConfigFinding[] {
    const findings: ConfigFinding[] = [];
    for (const s of visibleSpecs(specs, value)) {
        const v = value[s.key];
        if (isBlank(v)) {
            if (isRequired(s)) {
                findings.push({ severity: 'error', path: s.key, message: `${s.label} is required` });
            }
            continue;
        }
        switch (s.type) {
            case 'number': {
                const n = typeof v === 'number' ? v : Number(v);
                if (Number.isNaN(n)) {
                    findings.push({ severity: 'error', path: s.key, message: `${s.label} must be a number` });
                    break;
                }
                if (s.min !== undefined && n < s.min) {
                    findings.push({ severity: 'error', path: s.key, message: `${s.label} must be ≥ ${s.min}` });
                }
                if (s.max !== undefined && n > s.max) {
                    findings.push({ severity: 'error', path: s.key, message: `${s.label} must be ≤ ${s.max}` });
                }
                break;
            }
            case 'boolean':
                if (typeof v !== 'boolean') {
                    findings.push({ severity: 'error', path: s.key, message: `${s.label} must be true or false` });
                }
                break;
            case 'select':
                if (!(s.options ?? []).some((o) => o.value === v)) {
                    findings.push({ severity: 'error', path: s.key, message: `${s.label} must be one of the listed options` });
                }
                break;
            case 'identifier':
                if (typeof v !== 'string' || !IDENTIFIER_RE.test(v)) {
                    findings.push({
                        severity: 'error',
                        path: s.key,
                        message: `${s.label} must start with a letter and use only letters, digits, _ or -`,
                    });
                }
                break;
            default: // string / multiline
                break;
        }
        if (s.pattern && typeof v === 'string' && !new RegExp(`^(?:${s.pattern})$`).test(v)) {
            findings.push({ severity: 'error', path: s.key, message: `${s.label} has an invalid format` });
        }
    }
    return findings;
}

/** A ready-made `config.validate` for kinds whose config is fully described by their specs. */
export function attributeValidator(specs: AttributeSpec[]): (config: unknown) => ConfigFinding[] {
    return (config: unknown) =>
        validateAttributes(specs, (config ?? {}) as Record<string, unknown>);
}
