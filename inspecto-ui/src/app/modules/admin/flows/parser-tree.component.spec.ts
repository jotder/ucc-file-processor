import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { ParserTreeNode } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ParserTreeComponent } from './parser-tree.component';

const NODES: ParserTreeNode[] = [
    {
        label: 'record[0]', type: 'SEQUENCE', children: [
            { label: 'id', type: 'INTEGER', value: '1001' },
            { label: 'msisdn', type: 'string', value: '8801700000001' },
        ],
    },
];

function create(nodes: ParserTreeNode[] = NODES) {
    TestBed.configureTestingModule({
        imports: [ParserTreeComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(ParserTreeComponent);
    fixture.componentRef.setInput('nodes', nodes);
    fixture.detectChanges();
    return fixture;
}

describe('ParserTreeComponent', () => {
    it('renders node labels, type chips and leaf values', () => {
        const text = create().nativeElement.textContent ?? '';
        expect(text).toContain('record[0]');
        expect(text).toContain('SEQUENCE');
        expect(text).toContain('8801700000001');
    });

    it('default-expands containers and collapses on toggle', () => {
        const c = create().componentInstance;
        expect(c.isOpen(0)).toBe(true);
        c.toggle(0);
        expect(c.isOpen(0)).toBe(false);
        c.toggle(0);
        expect(c.isOpen(0)).toBe(true);
    });

    it('marks the top level as an ARIA tree', () => {
        const root = create().nativeElement.querySelector('[role="tree"]');
        expect(root).toBeTruthy();
    });

    it('has no a11y violations', async () => {
        await expectNoA11yViolations(create().nativeElement);
    });
});
