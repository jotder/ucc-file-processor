import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { GammaConfigService } from '@gamma/services/config';
import { HealthService, JobsService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { of, throwError } from 'rxjs';
import { MaintenanceOverviewComponent } from './maintenance-overview.component';

/** The grid/theme chain needs these two stubs (the alerts spec precedent). */
const HOST_STUBS = [
  { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
  { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
];

/** System Maintenance → Overview (MNT-11): composes /health/details + jobs + runs + artifacts. */
describe('MaintenanceOverviewComponent', () => {
  const health = {
    status: 'UP',
    subsystems: {
      configStore: { status: 'UP', detail: '/wr' },
      jobRunsProjection: { status: 'NOT_CONFIGURED', detail: '-Djobs.backend not set' },
    },
  };
  const jobs = [
    { name: 'config_backup', type: 'maintenance', cron: '0 4 * * *', enabled: true, lastStatus: 'SUCCESS' },
    { name: 'orders_rollup', type: 'pipeline', enabled: true },
  ];

  it('renders subsystems, the maintenance fleet, storage and backup — and passes axe', async () => {
    await TestBed.configureTestingModule({
      imports: [MaintenanceOverviewComponent],
      providers: [
        provideNoopAnimations(),
        ...HOST_STUBS,
        { provide: HealthService, useValue: { details: () => of(health) } },
        {
          provide: JobsService,
          useValue: {
            list: () => of(jobs),
            recentRuns: () =>
              of([
                { runId: 'r1', job: 'config_backup', type: 'maintenance', trigger: 'schedule',
                  startTime: '2026-07-12 03:00:00', endTime: '2026-07-12 03:00:01',
                  status: 'FAILED', durationMs: 10, message: 'disk full' },
                { runId: 'r2', job: 'orders_rollup', type: 'pipeline', trigger: 'schedule',
                  startTime: '2026-07-12 03:05:00', endTime: '2026-07-12 03:05:01',
                  status: 'FAILED', durationMs: 10, message: 'not maintenance — excluded' },
              ]),
            latestArtifacts: () =>
              of([
                { runId: 'r0', job: 'config_backup', seq: 1, name: 'backup', kind: 'file',
                  ref: 'backups/demo_config_20260712.zip', rows: 0, bytes: 2048, at: '' },
                { runId: 'r0', job: 'config_backup', seq: 2, name: 'axis:config', kind: 'file',
                  ref: 'config', rows: 0, bytes: 182_000, at: '' },
              ]),
          },
        },
      ],
    }).compileComponents();   // the data-table @defer needs compiled templates before create
    const fixture = TestBed.createComponent(MaintenanceOverviewComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('h1').length).toBe(1);
    expect(el.textContent).toContain('configStore');
    expect(el.textContent).toContain('NOT_CONFIGURED');
    expect(el.textContent).toContain('Last backup');
    expect(el.textContent).toContain('2.0 KB');
    expect(el.textContent).toContain('config');
    await expectNoA11yViolations(el);
  });

  it('degrades gracefully: health error + run projection off never blank the page', async () => {
    await TestBed.configureTestingModule({
      imports: [MaintenanceOverviewComponent],
      providers: [
        provideNoopAnimations(),
        ...HOST_STUBS,
        { provide: HealthService, useValue: { details: () => throwError(() => ({ status: 503 })) } },
        {
          provide: JobsService,
          useValue: {
            list: () => of([]),
            recentRuns: () => throwError(() => ({ status: 404 })),
            latestArtifacts: () => of([]),
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(MaintenanceOverviewComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('Health details unavailable');
    expect(el.textContent).toContain('Run projection not enabled');
    expect(el.textContent).toContain('No storage report yet');
  });
});
