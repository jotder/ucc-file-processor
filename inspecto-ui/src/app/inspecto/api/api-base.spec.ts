import { describe, expect, it } from 'vitest';
import { apiUrl, toParams } from './api-base';
import { environment } from '../../../environments/environment';

describe('apiUrl', () => {
  it('prefixes the path with the configured base and the v1 segment (W7)', () => {
    expect(apiUrl('/pipelines')).toBe(`${environment.apiBaseUrl}/v1/pipelines`);
  });
});

describe('toParams', () => {
  it('keeps non-empty values', () => {
    const p = toParams({ from: '2020-01-01', to: '2020-02-01' });
    expect(p.get('from')).toBe('2020-01-01');
    expect(p.get('to')).toBe('2020-02-01');
  });

  it('drops null, undefined and empty-string values', () => {
    const p = toParams({ a: null, b: undefined, c: '', d: 'keep' });
    expect(p.has('a')).toBe(false);
    expect(p.has('b')).toBe(false);
    expect(p.has('c')).toBe(false);
    expect(p.get('d')).toBe('keep');
  });

  it('joins array values with commas', () => {
    const p = toParams({ kinds: ['source', 'table'] });
    expect(p.get('kinds')).toBe('source,table');
  });

  it('stringifies numbers and booleans', () => {
    const p = toParams({ depth: 2, overlay: true });
    expect(p.get('depth')).toBe('2');
    expect(p.get('overlay')).toBe('true');
  });
});
