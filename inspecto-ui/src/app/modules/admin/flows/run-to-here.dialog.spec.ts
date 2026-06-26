import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { ConnectionProbeService, FlowRunResult, FlowsService, ResourceNode } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { RunToHereData, RunToHereDialog } from './run-to-here.dialog';

const RUN_RESULT: FlowRunResult = {
    seedNode: 'collect',
    toNode: 'parse',
    files: ['inbox/feed_001.csv.gz'],
    relations: [
        { node: 'parse', rel: 'success', rowCount: 3, rows: [{ id: 1 }, { id: 2 }, { id: 3 }] },
        { node: 'parse', rel: 'unmatched', rowCount: 1, rows: [{ line: 7 }] },
    ],
    output: { store: 'Parse CSV', format: 'PARQUET', path: 'data/_scratch/cdr_ingest/parse/part-0001.parquet', rowCount: 3 },
    warnings: [],
};

function create(data: Partial<RunToHereData> = {}) {
    TestBed.configureTestingModule({
        imports: [RunToHereDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: () => {} } },
            { provide: MAT_DIALOG_DATA, useValue: { flowId: 'cdr_ingest', node: { id: 'parse', type: 'parser.dsv' }, connectionId: null, ...data } },
            { provide: FlowsService, useValue: { runToNode: () => of(RUN_RESULT) } },
            { provide: ConnectionProbeService, useValue: { explore: () => of([]) } },
        ],
    });
    const fixture = TestBed.createComponent(RunToHereDialog);
    fixture.detectChanges();
    return fixture;
}

describe('RunToHereDialog', () => {
    it('runs the subgraph and exposes the per-relation result + Parquet output', () => {
        const c = create().componentInstance;
        c.run();
        expect(c.result()?.output?.rowCount).toBe(3);
        expect(c.result()?.relations.map((r) => `${r.node}/${r.rel}`)).toEqual(['parse/success', 'parse/unmatched']);
    });

    it('toggles a file into and out of the selection', () => {
        const c = create().componentInstance;
        const file: ResourceNode = { name: 'feed_001.csv.gz', path: 'inbox/feed_001.csv.gz', kind: 'file', hasChildren: false };
        c.onSelect(file);
        expect(c.selectedFiles()).toEqual(['inbox/feed_001.csv.gz']);
        c.onSelect(file);
        expect(c.selectedFiles()).toEqual([]);
    });

    it('has no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
