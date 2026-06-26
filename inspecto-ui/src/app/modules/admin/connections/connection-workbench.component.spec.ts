import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import {
    ConnectionProbeResult,
    ConnectionProbeService,
    ConnectionProfile,
    ConnectionsService,
    ResourceNode,
    SampleResult,
} from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ConnectionWorkbenchComponent } from './connection-workbench.component';

const PROFILE: ConnectionProfile = { id: 'TEST_CONN', connector: 'sftp', host: 'h', port: 22, basePath: '/in' };
const NODES: ResourceNode[] = [
    { name: 'inbox', path: 'inbox', kind: 'dir', hasChildren: true },
    { name: 'a.csv', path: 'a.csv', kind: 'file', hasChildren: false, sizeBytes: 10 },
];
const PROBE_R: ConnectionProbeResult = {
    id: 'TEST_CONN',
    connector: 'sftp',
    endpoint: 'h:22',
    ok: true,
    secretsResolved: true,
    checks: [{ check: 'reachability', ok: true, detail: 'ok', latencyMs: 5 }],
};
const SAMPLE_R: SampleResult = { path: 'a.csv', columns: ['id'], rows: [{ id: 1 }], truncated: false };

function create() {
    TestBed.configureTestingModule({
        imports: [ConnectionWorkbenchComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'TEST_CONN' }) } } },
            { provide: ConnectionsService, useValue: { get: () => of(PROFILE) } },
            { provide: ConnectionProbeService, useValue: { probe: () => of(PROBE_R), explore: () => of(NODES), sample: () => of(SAMPLE_R) } },
            { provide: ToastrService, useValue: { warning: () => undefined, success: () => undefined, error: () => undefined } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    return TestBed.createComponent(ConnectionWorkbenchComponent);
}

describe('ConnectionWorkbenchComponent', () => {
    it('loads the connection and explores the root on init', () => {
        const c = create().componentInstance;
        expect(c.connection()?.id).toBe('TEST_CONN');
        expect(c.rootNodes().length).toBe(2);
    });

    it('runProbe stores the graded result and classifies each check', () => {
        const c = create().componentInstance;
        c.runProbe();
        expect(c.probe()?.ok).toBe(true);
        expect(c.checkStatus({ check: 'write', ok: false, skipped: true, detail: '' })).toBe('SKIPPED');
        expect(c.checkStatus({ check: 'read', ok: false, detail: '' })).toBe('FAILED');
    });

    it('selecting a file loads a sample; selecting a dir expands it', () => {
        const c = create().componentInstance;
        c.onSelect(NODES[1]); // a.csv
        expect(c.sample()?.path).toBe('a.csv');
        c.onSelect(NODES[0]); // inbox (dir)
        expect(c.expanded().has('inbox')).toBe(true);
    });

    it('renders the initial workbench with no a11y violations', async () => {
        const fixture = create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
