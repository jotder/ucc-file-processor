import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InboxStatus, RunsService, SourceView } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SourceDetailDialog } from './source-detail.dialog';

const SOURCE: SourceView = {
    pipeline: 'cdr',
    id: 'sftp-main',
    connector: 'sftp',
    connection: 'ops-sftp',
    includes: ['*.csv'],
    excludes: ['*.tmp'],
    recursiveDepth: 2,
    duplicateMode: 'HASH',
    duplicateOnChange: 'REPROCESS',
    guarantee: 'AT_LEAST_ONCE',
    incrementalWatermark: 'mtime',
    fetchParallel: 4,
    fetchRateLimit: 10,
    postAction: 'ARCHIVE',
    dbWatermarkCurrent: '2026-06-17 10:00:00',
};

const STATUS: InboxStatus = { pipeline: 'cdr', inbox: '/in/cdr', pending: 3, running: true };

function create() {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [SourceDetailDialog],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: MAT_DIALOG_DATA, useValue: SOURCE },
            { provide: MatDialogRef, useValue: ref },
            { provide: RunsService, useValue: { pending: vi.fn(() => of(STATUS)) } },
        ],
    });
    const fixture = TestBed.createComponent(SourceDetailDialog);
    fixture.detectChanges();
    return { fixture, ref };
}

describe('SourceDetailDialog', () => {
    it('renders the live inbox status and the config grid', () => {
        const { fixture } = create();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Processing');
        expect(text).toContain('Pending: 3');
        expect(text).toContain('ops-sftp');
        expect(text).toContain('*.csv');
        expect(fixture.componentInstance.statusLoading).toBe(false);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
