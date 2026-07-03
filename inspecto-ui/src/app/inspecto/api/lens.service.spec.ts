import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { LensService } from './lens.service';

describe('LensService', () => {
    beforeEach(() => localStorage.clear());

    it('defaults to the builder lens when nothing is stored', () => {
        const service = TestBed.inject(LensService);
        expect(service.currentLens()).toBe('builder');
        expect(service.readOnly()).toBe(false);
    });

    it('restores a previously persisted lens', () => {
        localStorage.setItem('inspecto.currentLens', 'ops');
        const service = TestBed.inject(LensService);
        expect(service.currentLens()).toBe('ops');
    });

    it('falls back to the default on a corrupt stored value', () => {
        localStorage.setItem('inspecto.currentLens', 'nonsense');
        const service = TestBed.inject(LensService);
        expect(service.currentLens()).toBe('builder');
    });

    it('selectLens updates the signal and persists', () => {
        const service = TestBed.inject(LensService);
        service.selectLens('business');
        expect(service.currentLens()).toBe('business');
        expect(localStorage.getItem('inspecto.currentLens')).toBe('business');
    });

    it('readOnly is true only for the business lens', () => {
        const service = TestBed.inject(LensService);
        service.selectLens('business');
        expect(service.readOnly()).toBe(true);
        service.selectLens('builder');
        expect(service.readOnly()).toBe(false);
        service.selectLens('ops');
        expect(service.readOnly()).toBe(false);
    });

    it('capabilities (the RBAC seam) all deny in the business lens and grant otherwise', () => {
        const service = TestBed.inject(LensService);
        service.selectLens('business');
        expect(service.canAuthorWorkbench()).toBe(false);
        expect(service.canOperateRuns()).toBe(false);
        expect(service.canTriageRequirements()).toBe(false);
        service.selectLens('ops');
        expect(service.canAuthorWorkbench()).toBe(true);
        expect(service.canOperateRuns()).toBe(true);
        expect(service.canTriageRequirements()).toBe(true);
    });

    it('exposes the three lenses in display order', () => {
        expect(LensService.LENSES.map((l) => l.id)).toEqual(['business', 'builder', 'ops']);
    });
});
