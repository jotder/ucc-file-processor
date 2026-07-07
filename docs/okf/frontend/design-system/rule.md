---
type: Module
title: Rule (Pro Max templates)
description: Parameterized rule templates saved from the data-table; mock-backed via the `rule` component type.
resource: inspecto-ui/src/app/inspecto/rule/index.ts
tags: [design-system, rule, template, params, pro-max]
timestamp: 2026-06-28T00:00:00Z
---

# Rule

`inspecto/rule` is the Pro Max artifact of the [data-table](data-table.md): saving a query as a reusable,
parameterized **rule template**.

* `RuleTemplate` (`rule-types.ts`): `{ id, name, source, projection, where, sqlOverride?, params?, paramSql? }`. The body IS a [query](query.md) `QueryModel`; condition values are surfaced as named `params` (`:fieldValue` binds) with `paramSql` (the SQL with `:name` placeholders). `buildRuleTemplate(name, source, model, { params, paramSql })`.
* `RulesService` (`rules.service.ts`): `list()`/`save()`/`remove()` over the **`rule`** [component type](../conventions/mock-backends.md) (mock-backed now; real backend later). `'rule'` is a `ComponentType` but is intentionally **not** in the `COMPONENT_TYPES` palette.
* `RuleSaveDialog` (`rule-save.dialog.ts`): names the template and shows one editable property per parameter (e.g. `:tariffValue — tariff =`), with a parameterized SQL preview. Opened by the data-table proMax "save as rule" control; closes with the saved `RuleTemplate`.

# Examples

A condition `tariff = 'premium'` saves as `WHERE "tariff" = :tariffValue` with a `:tariffValue` param
defaulting to `premium`.
