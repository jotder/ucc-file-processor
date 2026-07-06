import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DashboardsService } from 'app/modules/admin/studio/dashboards/dashboards.service';
import { MenuArtifactComponent } from './menu-artifact.component';

/** Only the no-binding (empty-state) path is unit-tested — a real binding instantiates the heavy render
 *  children (chart / MapLibre / G6) that jsdom can't create; those render paths are verified live. */
describe('MenuArtifactComponent', () => {
    it('renders the empty state (with a custom message) when there is no binding', async () => {
        TestBed.configureTestingModule({
            imports: [MenuArtifactComponent],
            providers: [provideNoopAnimations(), { provide: DashboardsService, useValue: { get: () => of(null) } }],
        });
        const f = TestBed.createComponent(MenuArtifactComponent);
        f.componentRef.setInput('emptyMessage', 'Pick a report to preview.');
        f.detectChanges();
        expect(f.nativeElement.textContent).toContain('Nothing linked yet');
        expect(f.nativeElement.textContent).toContain('Pick a report to preview.');
        await expectNoA11yViolations(f.nativeElement);
    });
});
