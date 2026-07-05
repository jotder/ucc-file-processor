import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { AuditRow, RunsService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { BatchDetailDialog } from './batch-detail.dialog';

const BATCHES: AuditRow[] = [
    { batch_id: 'b-1', status: 'COMMITTED', files: '2' },
    { batch_id: 'b-2', status: 'OPEN', files: '1' },
];
const FILES: AuditRow[] = [
    { batch_id: 'b-1', file: 'a.csv', rows: '10' },
    { batch_id: 'b-1', file: 'b.csv', rows: '20' },
    { batch_id: 'b-2', file: 'c.csv', rows: '5' },
];
const LINEAGE: AuditRow[] = [{ input_file: 'a.csv', output: 'cdr/part-0.parquet' }];

function create() {
    const stub = {
        batches: vi.fn(() => of(BATCHES)),
        files: vi.fn(() => of(FILES)),
        lineage: vi.fn(() => of(LINEAGE)),
    };
    TestBed.configureTestingModule({
        imports: [BatchDetailDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { pipeline: 'cdr', batchId: 'b-1' } },
            { provide: MatDialogRef, useValue: { close: vi.fn() } },
            { provide: RunsService, useValue: stub },
            InspectoGridThemeService,
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    const fixture = TestBed.createComponent(BatchDetailDialog);
    fixture.detectChanges();   // runs ngOnInit (loads batches/files/lineage)
    return { fixture, stub };
}

describe('BatchDetailDialog', () => {
    it('resolves the summary row, member files and lineage for the batch', () => {
        const { fixture, stub } = create();
        const c = fixture.componentInstance;
        expect(stub.lineage).toHaveBeenCalledWith('cdr', 'b-1');
        expect(c.loading).toBe(false);
        expect(c.batchRow?.['batch_id']).toBe('b-1');
        expect(c.batchFiles.map((f) => f['file'])).toEqual(['a.csv', 'b.csv']);
        expect(c.batchLineage).toEqual(LINEAGE);
        expect(c.batchSummary.map((kv) => kv.key)).toContain('status');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
