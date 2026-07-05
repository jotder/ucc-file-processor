import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineEditorGraphComponent } from './pipeline-editor-graph.component';

/** G6 can't instantiate in jsdom (per the angular-ui skill) and this host mounts the canvas
 *  unconditionally in ngAfterViewInit — so `rebuild()` is stubbed out before the first
 *  detectChanges and only the canvas-free host shell (drop target, keyboard, aria) is tested. */
function create() {
    TestBed.configureTestingModule({
        imports: [PipelineEditorGraphComponent],
        providers: [{ provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } }],
    });
    const fixture = TestBed.createComponent(PipelineEditorGraphComponent);
    vi.spyOn(fixture.componentInstance as unknown as { rebuild(): void }, 'rebuild').mockImplementation(() => undefined);
    fixture.detectChanges();
    return fixture;
}

describe('PipelineEditorGraphComponent', () => {
    it('emits deleteKey on Delete and dropAdd from a palette drop', () => {
        const fixture = create();
        const c = fixture.componentInstance;

        const deleted = vi.fn();
        c.deleteKey.subscribe(deleted);
        c.onKeydown(new KeyboardEvent('keydown', { key: 'Delete' }));
        expect(deleted).toHaveBeenCalledTimes(1);

        const dropped = vi.fn();
        c.dropAdd.subscribe(dropped);
        // jsdom has no DataTransfer/DragEvent constructors — a shaped stand-in is enough here.
        const drop = {
            preventDefault: () => undefined,
            dataTransfer: { getData: (t: string) => (t === 'text/flow-node-type' ? 'collector' : '') },
            clientX: 10,
            clientY: 20,
        } as unknown as DragEvent;
        c.onDrop(drop);
        expect(dropped).toHaveBeenCalledWith(expect.objectContaining({ type: 'collector' }));
    });

    it('renders the canvas host (empty, no graph mounted) with no a11y violations', async () => {
        const fixture = create();
        const host = (fixture.nativeElement as HTMLElement).querySelector('[role="application"]');
        expect(host?.getAttribute('aria-label')).toContain('Pipeline editor canvas');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
