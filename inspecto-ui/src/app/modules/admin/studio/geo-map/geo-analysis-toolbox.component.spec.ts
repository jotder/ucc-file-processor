import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { GeoPoint } from 'app/inspecto/geo';
import { GeoAnalysisFocus, GeoAnalysisToolboxComponent } from './geo-analysis-toolbox.component';

const HOUR = 3_600_000;
const TIMED: GeoPoint[] = [
    { id: 'a1', lat: 23.81, lon: 90.41, kind: 'device', label: 'A', time: 1 * HOUR },
    { id: 'a2', lat: 23.81, lon: 90.41, kind: 'device', label: 'A', time: 3 * HOUR },
    { id: 'b1', lat: 23.8101, lon: 90.4101, kind: 'device', label: 'B', time: 1 * HOUR },
    { id: 'b2', lat: 23.8101, lon: 90.4101, kind: 'device', label: 'B', time: 3 * HOUR },
    { id: 'c1', lat: 51.5, lon: -0.12, kind: 'device', label: 'C', time: 1 * HOUR },
];
const UNTIMED: GeoPoint[] = [{ id: 'x', lat: 1, lon: 1, kind: 'tower', label: 'X' }];

function make(points: GeoPoint[] = TIMED) {
    TestBed.configureTestingModule({
        imports: [GeoAnalysisToolboxComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(GeoAnalysisToolboxComponent);
    fixture.componentRef.setInput('points', points);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance };
}

describe('GeoAnalysisToolboxComponent', () => {
    it('analysisReady reflects whether the points carry entity + time', () => {
        const { fixture, c } = make(UNTIMED);
        expect(c.analysisReady()).toBe(false); // untimed → the hint, no tools
        fixture.componentRef.setInput('points', TIMED);
        expect(c.analysisReady()).toBe(true);
    });

    it('co-location folds timed points and a result click emits a focus request', () => {
        const { c } = make(TIMED);
        let focused: GeoAnalysisFocus | undefined;
        c.focus.subscribe((f) => (focused = f));

        c.analysisTool.set('coloc');
        c.runAnalysis();
        expect(c.colocs()).toHaveLength(1);
        expect(c.colocs()[0].count).toBe(2);

        c.pick(c.colocs()[0].pointIds, 23.81, 90.41);
        expect(focused).toEqual({ pointIds: c.colocs()[0].pointIds, lat: 23.81, lon: 90.41 });
    });

    it('frequent locations run, then reset clears every result', () => {
        const { c } = make(TIMED);
        c.analysisTool.set('frequent');
        c.runAnalysis();
        expect(c.freqs().map((f) => f.entity).sort()).toEqual(['A', 'B']);
        expect(c.analysisRan()).toBe(true);

        c.reset();
        expect(c.freqs()).toHaveLength(0);
        expect(c.colocs()).toHaveLength(0);
        expect(c.analysisRan()).toBe(false);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = make(TIMED);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
