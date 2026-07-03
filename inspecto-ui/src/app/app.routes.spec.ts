import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { LensService } from 'app/inspecto/api';
import { LENS_HOME, lensHomeRedirect } from './app.routes';

describe('lensHomeRedirect (W4 per-lens home page)', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('maps each lens to its documented home route', () => {
        expect(LENS_HOME).toEqual({ business: 'kpi-reports', builder: 'pipelines', ops: 'events' });
    });

    it('redirects to the current lens\'s home route', () => {
        const lens = TestBed.inject(LensService);
        lens.selectLens('business');
        expect(TestBed.runInInjectionContext(() => lensHomeRedirect())).toBe('kpi-reports');
        lens.selectLens('ops');
        expect(TestBed.runInInjectionContext(() => lensHomeRedirect())).toBe('events');
        lens.selectLens('builder');
        expect(TestBed.runInInjectionContext(() => lensHomeRedirect())).toBe('pipelines');
    });
});
