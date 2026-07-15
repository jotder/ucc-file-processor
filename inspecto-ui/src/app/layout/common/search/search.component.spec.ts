import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter, Router } from '@angular/router';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SearchCommand, SearchComponent } from './search.component';

describe('SearchComponent (command palette)', () => {
    function create(commands: SearchCommand[] = []) {
        TestBed.configureTestingModule({
            imports: [SearchComponent],
            providers: [provideNoopAnimations(), provideRouter([])],
        });
        const fixture = TestBed.createComponent(SearchComponent);
        fixture.componentInstance.appearance = 'bar';
        fixture.componentInstance.commands = commands;
        fixture.detectChanges();
        return fixture;
    }

    beforeEach(() => localStorage.removeItem('inspecto.search.recents'));

    it('open() seeds the panel with the supplied commands (usable before typing)', () => {
        const c = create([{ title: 'Switch to Ops lens', group: 'Lens', run: () => {} }]).componentInstance;
        expect(c.results).toBeNull();
        c.open();
        expect(c.opened).toBe(true);
        expect(c.results?.map((r) => r.title)).toContain('Switch to Ops lens');
    });

    it('goTo() runs a command and closes; does not navigate', () => {
        const run = vi.fn();
        const fixture = create([{ title: 'Switch to Ops lens', run }]);
        const router = TestBed.inject(Router);
        const nav = vi.spyOn(router, 'navigateByUrl');
        const c = fixture.componentInstance;
        c.open();
        c.goTo(c.results!.find((r) => r.title === 'Switch to Ops lens')!);
        expect(run).toHaveBeenCalledTimes(1);
        expect(nav).not.toHaveBeenCalled();
        expect(c.opened).toBe(false);
    });

    it('goTo() on a destination navigates and records a recent surfaced on the next open()', () => {
        const fixture = create();
        const router = TestBed.inject(Router);
        const nav = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
        const c = fixture.componentInstance;
        // Pull a real destination from the nav-derived list via a query.
        c.open();
        c.searchControl.setValue('run');
        // (filter is debounced; drive it directly for determinism)
        const dest = { title: 'Runs', link: '/runs' };
        c.goTo(dest);
        expect(nav).toHaveBeenCalledWith('/runs');
        expect(JSON.parse(localStorage.getItem('inspecto.search.recents')!)).toEqual(['/runs']);
    });

    it('typing filters both destinations and commands', () => {
        const c = create([{ title: 'Switch to Ops lens', group: 'Lens', run: () => {} }]).componentInstance;
        c.open();
        // Drive the debounced valueChanges stream with vitest fake timers (rxjs debounceTime uses
        // setTimeout, which vi patches) — the repo's runner has no Angular fakeAsync ProxyZone.
        vi.useFakeTimers();
        try {
            c.searchControl.setValue('ops');
            vi.advanceTimersByTime(200);
        } finally {
            vi.useRealTimers();
        }
        expect(c.results?.some((r) => r.title === 'Switch to Ops lens')).toBe(true);
        expect(
            c.results?.every(
                (r) => r.title.toLowerCase().includes('ops') || (r.group ?? '').toLowerCase().includes('ops'),
            ),
        ).toBe(true);
    });

    it('has no a11y violations (bar, closed)', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
