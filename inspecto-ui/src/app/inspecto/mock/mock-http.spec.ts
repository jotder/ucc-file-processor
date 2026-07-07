import { describe, expect, it } from 'vitest';
import { isV1Envelope } from '../api/v1';
import { v1ErrorBody, v1SuccessBody } from './mock-http';

describe('mock v1 envelope shaping (mirror of the backend Envelope seam)', () => {
    it('wraps a raw DTO in a v1 success envelope the unwrap guard recognizes', () => {
        const env = v1SuccessBody([{ name: 'mini_etl' }]);
        expect(env.data).toEqual([{ name: 'mini_etl' }]);
        expect(env.metadata.apiVersion).toBe('v1');
        expect(env.diagnostics.correlationId).toMatch(/^mock-/);
        expect(isV1Envelope(env)).toBe(true);
    });

    it('lifts a legacy {error: msg} body into the v1 ErrorObject with the status-default code', () => {
        const body = v1ErrorBody(404, { error: 'no such pipeline' });
        expect(body.error.errorCode).toBe('NOT_FOUND');
        expect(body.error.message).toBe('no such pipeline');
        expect(body.error.recoverable).toBe(true);
        expect(body.error.correlationId).toMatch(/^mock-/);
        expect(body.error.details).toBeUndefined();
    });

    it('preserves extra keys (422 findings) under details and maps the code', () => {
        const findings = [{ severity: 'ERROR', message: 'bad schema' }];
        const body = v1ErrorBody(422, { error: 'validation failed', findings });
        expect(body.error.errorCode).toBe('CONFIG_VALIDATION_FAILED');
        expect(body.error.details).toEqual({ findings });
    });

    it('marks 500s unrecoverable with the INTERNAL default code', () => {
        const body = v1ErrorBody(500, { error: 'boom' });
        expect(body.error.errorCode).toBe('INTERNAL');
        expect(body.error.recoverable).toBe(false);
    });
});
