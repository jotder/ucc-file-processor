import { allViz } from './viz-registry';
import { ChannelValue, ControlValues, FieldRole, VizField, VizFit, VizPlugin } from './viz-types';

/**
 * "Show Me" — rank the registered plugins for a field set (à la Tableau/Superset), and auto-assign fields to
 * a plugin's channels. Pure functions over {@link VizField}s; the explore UI calls these to seed the builder.
 */

interface FieldCounts {
    dim: number;
    measure: number;
    temporal: number;
}

function counts(fields: VizField[]): FieldCounts {
    return {
        dim: fields.filter((f) => f.role === 'dimension').length,
        measure: fields.filter((f) => f.role === 'measure').length,
        temporal: fields.filter((f) => f.role === 'temporal').length,
    };
}

/** Suitability score, or `-1` to disqualify (a hard `fit` constraint is violated). Higher = better fit. */
function fitScore(fit: VizFit, c: FieldCounts): number {
    if (fit.minMeasure != null && c.measure < fit.minMeasure) return -1;
    if (fit.minDim != null && c.dim < fit.minDim) return -1;
    if (fit.temporal === true && c.temporal === 0) return -1;

    let score = 0;
    if (fit.temporal === true && c.temporal > 0) score += 3;
    if (fit.temporal === false && c.temporal === 0) score += 1;
    if (fit.maxMeasure != null && c.measure > fit.maxMeasure) score -= 1; // tolerated, penalised
    if (fit.maxDim != null && c.dim > fit.maxDim) score -= 1;
    // Reward plugins whose measure appetite matches what's available.
    if (fit.minMeasure != null) score += Math.min(c.measure, fit.maxMeasure ?? c.measure);
    return score;
}

/** The plugins that fit the field set, best first. */
export function recommend(fields: VizField[]): VizPlugin[] {
    const c = counts(fields);
    return allViz()
        .map((p) => ({ p, score: fitScore(p.meta.fit, c) }))
        .filter((x) => x.score >= 0)
        .sort((a, b) => b.score - a.score)
        .map((x) => x.p);
}

/**
 * Greedily map fields onto a plugin's channels: each control takes the next unused field whose role it accepts
 * (acceptRoles are tried in declared order, so an `x` that accepts `['temporal','dimension']` prefers time).
 * Measure channels default to `sum`.
 */
export function autoAssignChannels(plugin: VizPlugin, fields: VizField[]): ControlValues {
    const pools: Record<FieldRole, VizField[]> = {
        temporal: fields.filter((f) => f.role === 'temporal'),
        measure: fields.filter((f) => f.role === 'measure'),
        dimension: fields.filter((f) => f.role === 'dimension'),
    };
    const used = new Set<string>();
    const values: ControlValues = {};

    for (const control of plugin.controls) {
        const pick = takeNext(control.acceptRoles, pools, used);
        if (!pick) continue;
        const cv: ChannelValue = control.isMeasure ? { field: pick.name, agg: 'sum' } : { field: pick.name };
        values[control.channel] = [cv];
    }
    return values;
}

function takeNext(roles: FieldRole[], pools: Record<FieldRole, VizField[]>, used: Set<string>): VizField | undefined {
    for (const role of roles) {
        const field = pools[role].find((f) => !used.has(f.name));
        if (field) {
            used.add(field.name);
            return field;
        }
    }
    return undefined;
}
