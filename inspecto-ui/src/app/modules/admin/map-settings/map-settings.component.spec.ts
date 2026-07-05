import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { GeoSettings, GeoSettingsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { MapSettingsComponent } from './map-settings.component';

function create(opts: { current?: string | null; failSave?: number } = {}) {
    const save = vi.fn((s: GeoSettings) =>
        opts.failSave ? throwError(() => ({ status: opts.failSave })) : of(s),
    );
    TestBed.configureTestingModule({
        imports: [MapSettingsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: GeoSettingsService, useValue: { get: () => of({ tileServerUrl: opts.current ?? null }), save } },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(MapSettingsComponent);
    fixture.detectChanges();
    return { fixture, save };
}

describe('MapSettingsComponent', () => {
    it('loads the stored tile server into the form and renders accessibly', async () => {
        const { fixture } = create({ current: 'https://t.example/{z}/{x}/{y}.png' });
        expect(fixture.componentInstance.form.controls.tileServerUrl.value).toBe('https://t.example/{z}/{x}/{y}.png');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('blocks a template without {z}/{x}/{y} placeholders inline; accepts pmtiles:// and empty', () => {
        const { fixture, save } = create();
        const ctrl = fixture.componentInstance.form.controls.tileServerUrl;
        ctrl.setValue('https://tiles.example.com/wms');
        fixture.componentInstance.save();
        expect(ctrl.hasError('tileTemplate')).toBe(true);
        expect(save).not.toHaveBeenCalled();
        ctrl.setValue('pmtiles://https://tiles.example.com/world.pmtiles');
        expect(ctrl.valid).toBe(true);
        ctrl.setValue('');
        expect(ctrl.valid).toBe(true);
    });

    it('saves the trimmed URL (empty → null) through the service', () => {
        const { fixture, save } = create();
        fixture.componentInstance.form.controls.tileServerUrl.setValue('  https://t.example/{z}/{x}/{y}.png  ');
        fixture.componentInstance.save();
        expect(save).toHaveBeenCalledWith({ tileServerUrl: 'https://t.example/{z}/{x}/{y}.png' });
        fixture.componentInstance.form.controls.tileServerUrl.setValue('');
        fixture.componentInstance.save();
        expect(save).toHaveBeenLastCalledWith({ tileServerUrl: null });
    });

    it('surfaces a 503 as the writes-disabled banner', () => {
        const { fixture } = create({ failSave: 503 });
        fixture.componentInstance.form.controls.tileServerUrl.setValue('');
        fixture.componentInstance.save();
        expect(fixture.componentInstance.writesDisabled()).toBe(true);
    });
});
