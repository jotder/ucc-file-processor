import * as axe from 'axe-core';

/**
 * axe-core accessibility assertion for component unit tests (vitest + jsdom).
 *
 * Runs axe against a rendered fixture's DOM and fails with a readable summary on any violation.
 * This is the automated half of the WCAG 2.2 AA work (UI/UX audit — Long-term #2); the manual
 * review lives in `docs/ui/accessibility-audit.md`.
 *
 * Rules disabled below either need real layout/painting (which jsdom does not do) or are
 * page-level checks that are meaningless against an isolated component fixture. Color-contrast in
 * particular cannot run in jsdom — it is covered in the manual audit and enforced for new code by
 * the design-system token guard (`npm run lint:tokens`).
 */
const DISABLED_RULES = [
    'color-contrast', // needs rendered colors/geometry — jsdom has none; see manual audit
    'region', // page-level: "all content in a landmark"
    'landmark-one-main',
    'page-has-heading-one',
    'html-has-lang',
    'document-title',
    'bypass',
] as const;

export async function expectNoA11yViolations(root: Element): Promise<void> {
    const results = await axe.run(root, {
        rules: Object.fromEntries(DISABLED_RULES.map((id) => [id, { enabled: false }])),
        resultTypes: ['violations'],
    });

    if (results.violations.length > 0) {
        const summary = results.violations
            .map(
                (v) =>
                    `  • [${v.impact}] ${v.id} — ${v.help}\n` +
                    `    ${v.nodes.length} node(s); e.g. ${v.nodes[0]?.target.join(' ')}\n` +
                    `    ${v.helpUrl}`,
            )
            .join('\n');
        throw new Error(
            `axe-core found ${results.violations.length} accessibility violation(s):\n${summary}`,
        );
    }
}
