import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { ResourceNode } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ConnectionTreeComponent } from './connection-tree.component';

const NODES: ResourceNode[] = [
    { name: 'inbox', path: 'inbox', kind: 'dir', hasChildren: true },
    { name: 'README.txt', path: 'README.txt', kind: 'file', hasChildren: false, sizeBytes: 482 },
];

describe('ConnectionTreeComponent', () => {
    it('renders nodes with no a11y violations', async () => {
        TestBed.configureTestingModule({ imports: [ConnectionTreeComponent], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(ConnectionTreeComponent);
        fixture.componentInstance.nodes = NODES;
        fixture.componentInstance.expanded = new Set(['inbox']);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('formats sizes in human units', () => {
        TestBed.configureTestingModule({ imports: [ConnectionTreeComponent], providers: [provideNoopAnimations()] });
        const c = TestBed.createComponent(ConnectionTreeComponent).componentInstance;
        expect(c.size(482)).toBe('482 B');
        expect(c.size(1024)).toBe('1.0 KB');
        expect(c.size(1_500_000)).toBe('1.4 MB');
    });
});
