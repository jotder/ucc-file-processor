import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ToastrService } from 'ngx-toastr';
import { catchError, of } from 'rxjs';

import {
    AccessService,
    apiErrorMessage,
    ExplainResult,
    PoliciesDoc,
    PolicyDef,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';

/**
 * Settings ▸ Access ▸ Policies (BACKLOG §5 — the policy-authoring operability slice): a read-only view
 * of the effective Access Policies (ABAC A2/A3) plus a "Why denied?" explainer. Two things the raw
 * `access-policies.toon` couldn't show an operator:
 *
 * <ul>
 *   <li><b>Seed visibility:</b> the engine-resident A4 space-isolation denies are never in the authored
 *       doc, so this table surfaces them tagged <code>seed</code> alongside the <code>authored</code> rows.</li>
 *   <li><b>Why denied:</b> the panel runs <code>GET /access/explain</code> for the caller's own session
 *       against a hypothetical route/method, showing the engine's decision, the matched policy, and a
 *       per-policy trace — the answer to "which policy blocked me?".</li>
 * </ul>
 *
 * Read-only by design: policy <em>authoring</em> stays TOON/API (the matrix editor is a separate, larger
 * follow-on). Enforcement is Enterprise-only — on Personal/Standard the explainer reports it's disabled.
 */
@Component({
    selector: 'app-access-policies',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        ChipComponent,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
    ],
    templateUrl: './access-policies.component.html',
})
export class AccessPoliciesComponent {
    private readonly api = inject(AccessService);
    private readonly fb = inject(FormBuilder);
    private readonly toastr = inject(ToastrService);

    readonly methods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

    readonly loading = signal(true);
    readonly rows = signal<PolicyDef[]>([]);
    /** Set ⇔ the authored access-policies.toon is unreadable (engine denies, fail-closed). */
    readonly docError = signal<string | null>(null);

    readonly explaining = signal(false);
    readonly result = signal<ExplainResult | null>(null);

    readonly hasPolicies = computed(() => this.rows().length > 0);

    readonly form = this.fb.group({
        route: ['', Validators.required],
        method: ['GET'],
        resourceKind: [''],
    });

    constructor() {
        this.load();
    }

    private load(): void {
        this.api
            .policies()
            .pipe(catchError(() => of<PoliciesDoc>({ policies: [] })))
            .subscribe((doc) => {
                this.docError.set(doc.error ?? null);
                this.rows.set(doc.policies ?? []);
                this.loading.set(false);
            });
    }

    /** Run the dry-run for the caller's own session against the form's route/method/resourceKind. */
    explain(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        this.explaining.set(true);
        this.api
            .explain({
                route: (v.route ?? '').trim(),
                method: v.method ?? 'GET',
                resourceKind: v.resourceKind?.trim() || undefined,
            })
            .subscribe({
                next: (r) => {
                    this.result.set(r);
                    this.explaining.set(false);
                },
                error: (err) => {
                    this.explaining.set(false);
                    this.result.set(null);
                    this.toastr.error(apiErrorMessage(err, 'Could not evaluate access'));
                },
            });
    }

    /** DENY → error, ALLOW → success, ABSTAIN/other → info — the inline result banner's variant. */
    decisionVariant(d: string | undefined): 'success' | 'error' | 'info' {
        return d === 'DENY' ? 'error' : d === 'ALLOW' ? 'success' : 'info';
    }

    /** A human summary of a policy's target dimensions ("any" when unconstrained). */
    target(p: PolicyDef): string {
        const t = p.target;
        const parts: string[] = [];
        if (t?.actions?.length) parts.push(t.actions.join(', '));
        if (t?.resourceKinds?.length) parts.push(t.resourceKinds.join(', '));
        return parts.length ? parts.join(' · ') : 'any';
    }
}
