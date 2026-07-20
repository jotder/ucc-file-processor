import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { PivotService } from './pivot.service';

describe('PivotService', () => {
    it('navigates to the target view with the selection carried as query params', () => {
        TestBed.configureTestingModule({ providers: [provideRouter([])] });
        const service = TestBed.inject(PivotService);
        const router = TestBed.inject(Router);
        const spy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

        service.pivotTo('map', { id: 'case-1', type: 'CASE' });
        expect(spy).toHaveBeenCalledWith(['/studio/geo-map'], { queryParams: { pivotId: 'case-1', pivotType: 'CASE' } });

        service.pivotTo('graph', { id: 'inc-1', type: 'INCIDENT' });
        expect(spy).toHaveBeenCalledWith(['/studio/link-analysis'], { queryParams: { pivotId: 'inc-1', pivotType: 'INCIDENT' } });
    });

    it('reads a valid incoming pivot off the route query params', () => {
        TestBed.configureTestingModule({ providers: [provideRouter([])] });
        const service = TestBed.inject(PivotService);
        const route = {
            snapshot: { queryParamMap: convertToParamMap({ pivotId: 'case-1', pivotType: 'CASE' }) },
        } as unknown as ActivatedRoute;

        expect(service.readIncoming(route)).toEqual({ id: 'case-1', type: 'CASE' });
    });

    it('ignores missing or malformed query params', () => {
        TestBed.configureTestingModule({ providers: [provideRouter([])] });
        const service = TestBed.inject(PivotService);
        const empty = { snapshot: { queryParamMap: convertToParamMap({}) } } as unknown as ActivatedRoute;
        const badType = {
            snapshot: { queryParamMap: convertToParamMap({ pivotId: 'x', pivotType: 'BOGUS' }) },
        } as unknown as ActivatedRoute;

        expect(service.readIncoming(empty)).toBeUndefined();
        expect(service.readIncoming(badType)).toBeUndefined();
    });
});
