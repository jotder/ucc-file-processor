import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { MenuNode, MenuService } from 'app/inspecto/menu';
import { DashboardsService } from 'app/modules/admin/studio/dashboards/dashboards.service';
import { MenuItemHostComponent } from './menu-item-host.component';

/** Build the host against a fake route param + a stubbed MenuService.find. Caller decides whether to
 *  render: the empty-state paths are safe, but a real binding would instantiate the heavy render children
 *  (chart / MapLibre / G6), which jsdom can't create — those are exercised live (see the plan, M3). */
function configure(node: MenuNode | undefined, nodeId = 'n1'): ComponentFixture<MenuItemHostComponent> {
    TestBed.configureTestingModule({
        imports: [MenuItemHostComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ nodeId })) } },
            { provide: MenuService, useValue: { find: (id: string) => (id === nodeId ? node : undefined) } },
            { provide: DashboardsService, useValue: { get: () => of(null) } },
        ],
    });
    return TestBed.createComponent(MenuItemHostComponent);
}

describe('MenuItemHostComponent', () => {
    it('shows a not-found empty state for an unknown node', async () => {
        const f = configure(undefined);
        f.detectChanges();
        expect(f.nativeElement.textContent).toContain('Menu item not found');
        await expectNoA11yViolations(f.nativeElement);
    });

    it('shows a "nothing linked" empty state for a group node (no binding), titled by the node', () => {
        const f = configure({ id: 'n1', title: 'Revenue', children: [] });
        f.detectChanges();
        expect(f.nativeElement.querySelector('h1')?.textContent).toContain('Revenue');
        expect(f.nativeElement.textContent).toContain('Nothing linked');
    });

    it('resolves a leaf binding from the route param (no render — child hosts are live-only)', () => {
        const f = configure({ id: 'n1', title: 'Cost', binding: { kind: 'widget', componentId: 'cost_by_tariff' } });
        expect(f.componentInstance.binding()).toEqual({ kind: 'widget', componentId: 'cost_by_tariff' });
    });
});
