import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { GeocoderService } from './geocoder.service';

describe('GeocoderService', () => {
    it('ships one offline geocoder and delegates geocode() to it', async () => {
        TestBed.configureTestingModule({ providers: [GeocoderService] });
        const svc = TestBed.inject(GeocoderService);
        expect(svc.geocoders.map((g) => g.id)).toEqual(['offline']);
        expect(svc.active.id).toBe('offline');
        const results = await svc.geocode('dhaka');
        expect(results[0]?.name).toBe('Dhaka');
        expect(results[0]?.context).toBe('Bangladesh');
    });
});
