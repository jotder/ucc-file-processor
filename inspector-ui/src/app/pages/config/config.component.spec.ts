import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ConfigComponent } from './config.component';
import { FieldSpec } from '../../shared/api';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl;

const field = (path: string, type: FieldSpec['type'], extra: Partial<FieldSpec> = {}): FieldSpec =>
  ({ path, label: path, type, ...extra } as FieldSpec);

/**
 * Component-logic specs for {@link ConfigComponent}: the spec-driven form model. We construct the
 * component in an injection context (no DevExtreme rendering) and exercise the pure assembly /
 * editor-type logic plus the HTTP-backed spec load and draft validation.
 */
describe('ConfigComponent (logic)', () => {
  let comp: ConfigComponent;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    comp = TestBed.runInInjectionContext(() => new ConfigComponent());
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('assembles a nested config from the flat dotted model and drops empties', () => {
    comp.model = {
      name: 'X',
      'dirs.poll': 'in',
      'dirs.database': 'out',
      'processing.threads': 1,
      'processing.note': '',     // empty string → dropped
      'processing.tags': [],     // empty array → dropped
      missing: null,             // null → dropped
    };
    const assembled = JSON.parse(comp.assembledPreview);
    expect(assembled).toEqual({
      name: 'X',
      dirs: { poll: 'in', database: 'out' },
      processing: { threads: 1 },
    });
  });

  it('maps field specs to the right dynamic editor type', () => {
    expect(comp.editorType(field('p', 'STRING', { options: ['a', 'b'] }))).toBe('select');
    expect(comp.editorType(field('p', 'ARRAY'))).toBe('tags');
    expect(comp.editorType(field('p', 'INTEGER'))).toBe('number');
    expect(comp.editorType(field('p', 'BOOLEAN'))).toBe('boolean');
    expect(comp.editorType(field('p', 'STRING'))).toBe('text');
  });

  it('loadSpec seeds the model with field defaults', () => {
    comp.type = 'pipeline';
    comp.loadSpec();
    httpMock.expectOne(`${base}/config/spec/pipeline`).flush({
      type: 'pipeline',
      fields: [field('processing.threads', 'INTEGER', { default: 4 }), field('name', 'STRING')],
      rules: [],
    });
    expect(comp.spec).not.toBeNull();
    expect(comp.model['processing.threads']).toBe(4);
    expect(comp.model['name']).toBeUndefined(); // no default → not seeded
  });

  it('validateDraft posts the assembled config with the type and safety flag', () => {
    comp.type = 'pipeline';
    comp.safety = true;
    comp.model = { name: 'X', 'dirs.poll': 'in' };
    comp.validateDraft();
    const req = httpMock.expectOne(`${base}/validate`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      type: 'pipeline',
      config: { name: 'X', dirs: { poll: 'in' } },
      safety: true,
    });
    req.flush({ type: 'pipeline', findings: [], clean: true, safetyChecked: true });
    expect(comp.result?.clean).toBe(true);
    expect(comp.validating).toBe(false);
  });

  it('validateFile with a blank path notifies and makes no request', () => {
    comp.configPath = '   ';
    comp.validateFile();
    expect(comp.validating).toBe(false);
    // afterEach httpMock.verify() asserts no /validate call was made.
  });
});
