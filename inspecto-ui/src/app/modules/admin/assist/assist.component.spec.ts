import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { AssistService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AssistComponent } from './assist.component';

function create() {
    TestBed.configureTestingModule({
        imports: [AssistComponent],
        providers: [
            provideNoopAnimations(),
            { provide: AssistService, useValue: { run: () => of(null) } },
            { provide: ToastrService, useValue: {} },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    const fixture = TestBed.createComponent(AssistComponent);
    fixture.detectChanges();
    return fixture;
}

describe('AssistComponent', () => {
    it('starts on the first intent (kpi-to-sql)', () => {
        const c = create().componentInstance;
        expect(c.selected.id).toBe('kpi-to-sql');
    });

    it('switching the selected intent re-keys the panel (new instance per intent id)', () => {
        const c = create().componentInstance;
        expect(c.intents.map((i) => i.id)).toHaveLength(7);
        c.selected = c.intents.find((i) => i.id === 'explain-entity')!;
        expect(c.selected.id).toBe('explain-entity');
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
