import { ConditionGroup, QueryModel, SqlParam } from 'app/inspecto/query';

/**
 * A saved rule template — the Pro Max artifact. The body IS a {@link QueryModel} (projection + nested
 * AND/OR filter, or a hand-edited SQL override) plus an id/name and the source it queries. The condition
 * values are also surfaced as named **params** (`:fieldValue`) with `paramSql` — at runtime a rule engine /
 * job binds those params and runs it (real backend later; mock-backed `rule` component type for now). Pure
 * data — no Angular.
 */
export interface RuleTemplate {
    id: string;
    name: string;
    source: string;
    projection: string[] | '*';
    where: ConditionGroup;
    sqlOverride?: string | null;
    /** Named binds derived from the condition values, with their (editable) default values. */
    params?: SqlParam[];
    /** The SQL with `:name` placeholders in place of literals (illustrative; runs on the server once wired). */
    paramSql?: string;
}

/** Build a {@link RuleTemplate} from a finished query (the data-table Pro tier's model) + optional params. */
export function buildRuleTemplate(
    name: string,
    source: string,
    model: QueryModel,
    extras?: { params?: SqlParam[]; paramSql?: string },
): RuleTemplate {
    return {
        id: name,
        name,
        source,
        projection: model.projection,
        where: model.where,
        sqlOverride: model.sqlOverride ?? null,
        params: extras?.params,
        paramSql: extras?.paramSql,
    };
}
