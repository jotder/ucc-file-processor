import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { PipelineNodeType } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { NodeTypeGroup } from './pipeline-graph';
import { PipelinePaletteComponent } from './pipeline-palette.component';

const type = (t: string, category: string, label: string): PipelineNodeType =>
    ({ type: t, category, label, description: `Add a ${label}`, accepts: [], emits: [], emitsNamedRoutes: false });

const GROUPS: NodeTypeGroup[] = [
    { category: 'SOURCE', types: [type('collector.file', 'SOURCE', 'File')] },
    { category: 'SINK', types: [type('sink.file', 'SINK', 'File writer')] },
];

function create() {
    TestBed.configureTestingModule({
        imports: [PipelinePaletteComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(PipelinePaletteComponent);
    fixture.componentInstance.groups = GROUPS;
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance };
}

describe('PipelinePaletteComponent', () => {
    it('starts closed, showing only the trigger', () => {
        const { fixture } = create();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[aria-expanded="false"]')).toBeTruthy();
        expect(el.textContent).not.toContain('File writer');
    });

    it('opens the panel and lists every group + type', () => {
        const { fixture, c } = create();
        c.open.set(true);
        fixture.detectChanges();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('File');
        expect(text).toContain('File writer');
    });

    it('emits pick with the type id on click-to-add', () => {
        const { fixture, c } = create();
        c.open.set(true);
        fixture.detectChanges();
        const pick = vi.fn();
        c.pick.subscribe(pick);
        const btn = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((b) =>
            b.getAttribute('aria-label') === 'Add File',
        );
        btn?.click();
        expect(pick).toHaveBeenCalledWith('collector.file');
    });

    it('has no a11y violations closed or open', async () => {
        const { fixture, c } = create();
        await expectNoA11yViolations(fixture.nativeElement);
        c.open.set(true);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
