import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { LensService } from './lens.service';
import { SessionService } from './session.service';

describe('LensService', () => {
    beforeEach(() => {
        localStorage.clear();
        // LensService reads SessionService (the R2 grant source), which needs the HTTP/router DI.
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
        });
    });

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

    it('action grants pushed from Access Profiles re-derive the capabilities per lens', () => {
        const service = TestBed.inject(LensService);
        service.selectLens('ops');
        expect(service.canAuthorWorkbench()).toBe(true);
        service.setActionGrants({ 'workbench.author': { ops: false, builder: true } });
        expect(service.canAuthorWorkbench()).toBe(false); // denied for ops by the profile
        expect(service.canConfigureAccess()).toBe(true); // unlisted actions stay allowed
        service.selectLens('builder');
        expect(service.canAuthorWorkbench()).toBe(true); // grants are per lens
        service.setActionGrants(null); // profiles gone → pre-profile behavior
        service.selectLens('ops');
        expect(service.canAuthorWorkbench()).toBe(true);
    });

    it('honor system: every lens is allowed while authMode is none', () => {
        const service = TestBed.inject(LensService);
        expect(service.allowedLenses().map((l) => l.id)).toEqual(['business', 'builder', 'ops']);
    });

    it('under OIDC the capabilities re-derive from the /bootstrap effective grants (R2)', () => {
        const session = TestBed.inject(SessionService);
        const service = TestBed.inject(LensService);
        session.authMode.set('oidc');
        session.capabilities.set(['canAuthorWorkbench', 'canOperateRuns']);
        service.selectLens('ops');
        expect(service.canAuthorWorkbench()).toBe(true);
        expect(service.canOperateRuns()).toBe(true);
        expect(service.canAuthorAlertRules()).toBe(false); // not granted by the subject's roles
        expect(service.canConfigureAccess()).toBe(false);
        expect(service.canTriageRequirements()).toBe(false);
    });

    it('under OIDC the switcher is constrained to the lenses the grants project onto', () => {
        const session = TestBed.inject(SessionService);
        const service = TestBed.inject(LensService);
        session.authMode.set('oidc');
        session.capabilities.set(['canOperateRuns']); // an operations-only subject
        expect(service.allowedLenses().map((l) => l.id)).toEqual(['business', 'ops']);
        // the default (builder) preference is disallowed → coerced to the most capable allowed lens
        expect(service.currentLens()).toBe('ops');
        // a zero-grant subject is business-only
        session.capabilities.set([]);
        expect(service.allowedLenses().map((l) => l.id)).toEqual(['business']);
        expect(service.currentLens()).toBe('business');
    });

    it('a constrained-away preference is kept, not overwritten, and snaps back when re-granted', () => {
        localStorage.setItem('inspecto.currentLens', 'builder');
        const session = TestBed.inject(SessionService);
        const service = TestBed.inject(LensService);
        session.authMode.set('oidc');
        session.capabilities.set(['canOperateRuns']);
        expect(service.currentLens()).toBe('ops'); // coerced, but…
        expect(localStorage.getItem('inspecto.currentLens')).toBe('builder'); // …preference untouched
        session.capabilities.set(['canOperateRuns', 'canAuthorWorkbench']); // role restored
        expect(service.currentLens()).toBe('builder');
    });
});
