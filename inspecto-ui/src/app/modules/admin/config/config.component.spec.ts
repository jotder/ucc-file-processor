import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it } from 'vitest';
import { ConfigService, ConfigSpec } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { assembleConfig, ConfigComponent, toAttrSpecs } from './config.component';

// Field shape mirrors the backend `com.gamma.config.spec.FieldSpec` record.
const SPEC: ConfigSpec = {
    type: 'pipeline',
    fields: [
        { path: 'pipeline', label: 'Pipeline', type: 'STRING', required: true, description: 'Pipeline name' },
        { path: 'source.connector', label: 'Connector', type: 'ENUM', required: true, description: 'Connector', enumValues: ['sftp', 's3'] },
        { path: 'source.tags', label: 'Tags', type: 'LIST', required: false, description: 'Tags' },
        { path: 'source.enabled', label: 'Enabled', type: 'BOOL', required: false, description: 'Enabled' },
        { path: 'source.threads', label: 'Threads', type: 'INT', required: false, description: 'Reader threads', defaultValue: 2 },
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

describe('config spec → attribute mapping', () => {
    it('maps FieldSpec tiers and types (required→required tier, ENUM→select, LIST→string, INT→number)', () => {
        const specs = toAttrSpecs(SPEC.fields);
        expect(specs.map((s) => s.key)).toEqual(['f0', 'f1', 'f2', 'f3', 'f4']);
        // The control label is the dotted .toon path; the human label rides in the help text.
        expect(specs.map((s) => s.label)).toEqual(['pipeline', 'source.connector', 'source.tags', 'source.enabled', 'source.threads']);
        expect(specs[0].help).toBe('Pipeline — Pipeline name');
        expect(specs.map((s) => s.tier)).toEqual(['required', 'required', 'optional', 'optional', 'optional']);
        expect(specs[1].type).toBe('select');
        expect(specs[1].options).toEqual([{ value: 'sftp', label: 'sftp' }, { value: 's3', label: 's3' }]);
        expect(specs[2].type).toBe('string'); // LIST edits as comma-separated text
        expect(specs[3].type).toBe('boolean');
        expect(specs[4].type).toBe('number');
        expect(specs[4].default).toBe(2);
    });

    it('assembles the flat schema-form value into a nested config and splits LIST text', () => {
        const assembled = assembleConfig(SPEC.fields, { f0: 'cdr', f1: 'sftp', f2: ' a, b ,, ', f3: true, f4: 4 });
        expect(assembled).toEqual({ pipeline: 'cdr', source: { connector: 'sftp', tags: ['a', 'b'], enabled: true, threads: 4 } });
    });

    it('drops empties and empty LISTs from the assembled config', () => {
        const assembled = assembleConfig(SPEC.fields, { f0: 'cdr', f1: '', f2: ' , ', f3: null, f4: null });
        expect(assembled).toEqual({ pipeline: 'cdr' });
    });
});

describe('ConfigComponent', () => {
    it('renders the spec-driven schema form with no a11y violations', async () => {
        const fixture = create({});
        expect(fixture.componentInstance.spec).toEqual(SPEC);
        expect(fixture.nativeElement.querySelector('inspecto-schema-form')).toBeTruthy();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows the spec-unavailable empty state when the spec fails to load', async () => {
        const fixture = create({ spec: () => throwError(() => ({ status: 500 })) });
        expect(fixture.componentInstance.spec).toBeNull();
        expect(fixture.nativeElement.textContent).toContain('Spec unavailable');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('keeps a live assembled-config preview off the schema-form value', () => {
        const fixture = create({});
        const c = fixture.componentInstance;
        // Defaults flow into the preview immediately (source.threads: 2)…
        expect(JSON.parse(c.assembledPreview())).toEqual({ source: { threads: 2 } });
        // …and typing into f0 (the required `pipeline` field) flows in live.
        c['form']!.form.get('f0')!.setValue('cdr');
        expect(JSON.parse(c.assembledPreview())).toEqual({ pipeline: 'cdr', source: { threads: 2 } });
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
