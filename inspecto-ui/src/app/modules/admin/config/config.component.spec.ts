import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it } from 'vitest';
import { ConfigService, ConfigSpec } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ConfigComponent } from './config.component';

const SPEC: ConfigSpec = {
    type: 'pipeline',
    fields: [
        { path: 'pipeline', type: 'STRING', required: true, description: 'Pipeline name' },
        { path: 'source.connector', type: 'STRING', required: true, description: 'Connector', options: ['sftp', 's3'] },
        { path: 'source.tags', type: 'ARRAY', required: false, description: 'Tags' },
        { path: 'source.enabled', type: 'BOOLEAN', required: false, description: 'Enabled' },
    ],
    rules: [{ description: 'connector requires connection', affectedFields: ['source.connector'] }],
} as ConfigSpec;

function create(svc: Partial<ConfigService>) {
    TestBed.configureTestingModule({
        imports: [ConfigComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ConfigService, useValue: { spec: () => of(SPEC), ...svc } },
            { provide: ToastrService, useValue: { success: () => {}, warning: () => {}, error: () => {} } },
        ],
    });
    const fixture = TestBed.createComponent(ConfigComponent);
    fixture.detectChanges();
    return fixture;
}

describe('ConfigComponent', () => {
    it('renders the spec-driven form with no a11y violations', async () => {
        const fixture = create({});
        expect(fixture.componentInstance.spec).toEqual(SPEC);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows the spec-unavailable empty state when the spec fails to load', async () => {
        const fixture = create({ spec: () => throwError(() => ({ status: 500 })) });
        expect(fixture.componentInstance.spec).toBeNull();
        expect(fixture.nativeElement.textContent).toContain('Spec unavailable');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('assembles dotted paths into a nested config and normalizes tags', () => {
        const c = create({}).componentInstance;
        c.model['pipeline'] = 'cdr';
        c.model['source.connector'] = 'sftp';
        c.setTags('source.tags', ' a, b ,, ');
        const assembled = JSON.parse(c.assembledPreview);
        expect(assembled).toEqual({ pipeline: 'cdr', source: { connector: 'sftp', tags: ['a', 'b'] } });
        expect(c.tagsText('source.tags')).toBe('a, b');
    });

    it('renders findings with a severity badge', () => {
        const fixture = create({
            validateDraft: () =>
                of({
                    clean: false,
                    findings: [{ severity: 'ERROR', fieldPath: 'pipeline', message: 'Required field "pipeline" is missing.' }],
                    warnings: [],
                    safetyChecked: false,
                }),
        });
        fixture.componentInstance.validateDraft();
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.querySelector('inspecto-status-badge')).toBeTruthy();
        expect(el.textContent).toContain('Required field "pipeline" is missing.');
    });
});
