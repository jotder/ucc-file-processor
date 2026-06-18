import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideToastr } from 'ngx-toastr';
import { Observable, of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { ComponentDef, ComponentsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ComponentsComponent } from './components.component';

const GRAMMAR: ComponentDef = { type: 'grammar', name: 'pipe', ref: 'grammar/pipe', content: { delimiter: '|', has_header: true } };
const SCHEMA: ComponentDef = { type: 'schema', name: 'typed', ref: 'schema/typed', content: { fields: [{ name: 'id', type: 'integer' }] } };

/** Build the component over a stub service whose per-kind list() returns the given map. */
function create(lists: Partial<Record<string, Observable<ComponentDef[]>>>) {
    const stub = {
        list: (t: string) => lists[t] ?? of([]),
    } as unknown as ComponentsService;
    TestBed.configureTestingModule({
        imports: [ComponentsComponent],
        providers: [provideNoopAnimations(), provideToastr(), { provide: ComponentsService, useValue: stub }],
    });
    const fixture = TestBed.createComponent(ComponentsComponent);
    fixture.detectChanges(); // runs ngOnInit
    return fixture;
}

describe('ComponentsComponent', () => {
    it('lists components grouped by kind', () => {
        const c = create({ grammar: of([GRAMMAR]), schema: of([SCHEMA]) }).componentInstance;
        expect(c.countFor('grammar')).toBe(1);
        expect(c.countFor('schema')).toBe(1);
        expect(c.countFor('transform')).toBe(0);
    });

    it('summarises a component per kind', () => {
        const c = create({}).componentInstance;
        expect(c.summary(GRAMMAR)).toContain('header');
        expect(c.summary(SCHEMA)).toContain('1 field');
        expect(c.summary({ type: 'sink', name: 'o', ref: 'sink/o', content: { type: 'sink.view', store: 's' } })).toContain('sink.view');
    });

    it('renders an accessible empty state when there are no components', async () => {
        const fixture = create({});
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
