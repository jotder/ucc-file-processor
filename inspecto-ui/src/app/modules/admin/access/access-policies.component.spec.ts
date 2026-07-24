import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';

import { AccessService, ExplainResult, PolicyDef } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AccessPoliciesComponent } from './access-policies.component';

const AUTHORED: PolicyDef = {
    name: 'freeze-contractor',
    effect: 'deny',
    target: { actions: ['write', 'operate'] },
    when: "subject.employment == 'contractor'",
    source: 'authored',
};
const SEED: PolicyDef = { name: 'space-isolation', effect: 'deny', when: 'env.space != subject.space', source: 'seed' };

function create(opts: { policies?: PolicyDef[]; error?: string; explain?: ExplainResult } = {}) {
    const explain = vi.fn(() => of(opts.explain ?? { enabled: false, reason: 'no engine' }));
    const api = {
        policies: vi.fn(() => of({ policies: opts.policies ?? [], error: opts.error })),
        explain,
    };
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [AccessPoliciesComponent],
        providers: [
            provideNoopAnimations(),
            { provide: AccessService, useValue: api },
            { provide: ToastrService, useValue: toastr },
        ],
    });
    const fixture = TestBed.createComponent(AccessPoliciesComponent);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, explain, toastr };
}

describe('AccessPoliciesComponent', () => {
    it('lists effective policies with authored + built-in source badges', async () => {
        const { fixture } = create({ policies: [AUTHORED, SEED] });
        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('freeze-contractor');
        expect(text).toContain('space-isolation');
        expect(text).toContain('authored');
        expect(text).toContain('built-in');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows an empty state when nothing is authored and no seeds are in force', async () => {
        const { fixture } = create({ policies: [] });
        expect(fixture.nativeElement.textContent).toContain('No access policies');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('surfaces an unreadable authored document, fail-closed', () => {
        const { fixture } = create({ error: 'access-policies.toon is unreadable' });
        expect(fixture.nativeElement.textContent).toContain('unreadable');
    });

    it('requires a route before explaining', () => {
        const { c, explain } = create();
        c.explain();
        expect(explain).not.toHaveBeenCalled();
        expect(c.form.controls.route.touched).toBe(true);
    });

    it('reports a disabled engine without a trace', async () => {
        const { fixture, c } = create({ explain: { enabled: false, reason: 'no access policy engine on this edition' } });
        c.form.setValue({ route: '/access/roles', method: 'PUT', resourceKind: '' });
        c.explain();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('not active');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('renders the decision, matched policy, and per-policy trace when enabled', async () => {
        const result: ExplainResult = {
            enabled: true,
            subject: 'carl',
            action: 'write',
            route: '/access/roles',
            decision: 'DENY',
            matchedPolicy: 'freeze-contractor',
            trace: [
                { name: 'space-isolation', effect: 'deny', source: 'seed', targeted: false, conditionHeld: false },
                { name: 'freeze-contractor', effect: 'deny', source: 'authored', targeted: true, conditionHeld: true },
            ],
        };
        const { fixture, c, explain } = create({ explain: result });
        c.form.setValue({ route: '/access/roles', method: 'PUT', resourceKind: 'incident' });
        c.explain();
        fixture.detectChanges();

        expect(explain).toHaveBeenCalledWith({ route: '/access/roles', method: 'PUT', resourceKind: 'incident' });
        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('Decision: DENY');
        expect(text).toContain('freeze-contractor');
        expect(text).toContain('space-isolation');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
