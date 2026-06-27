import { ConditionGroup, QueryModel } from 'app/inspecto/query';

/**
 * A saved rule template — the Pro Max artifact. The body IS a {@link QueryModel} (projection + nested
 * AND/OR filter, or a hand-edited SQL override) plus an id/name and the source it queries. At runtime a rule
 * engine / job binds the source + any params and runs it (real backend later; mock-backed `rule` component
 * type for now). Pure data — no Angular.
 */
export interface RuleTemplate {
    id: string;
    name: string;
    source: string;
    projection: string[] | '*';
    where: ConditionGroup;
    sqlOverride?: string | null;
}

/** Build a {@link RuleTemplate} from a finished query (the data-table Pro tier's `queryChange.model`). */
export function buildRuleTemplate(name: string, source: string, model: QueryModel): RuleTemplate {
    return {
        id: name,
        name,
        source,
        projection: model.projection,
        where: model.where,
        sqlOverride: model.sqlOverride ?? null,
    };
}
