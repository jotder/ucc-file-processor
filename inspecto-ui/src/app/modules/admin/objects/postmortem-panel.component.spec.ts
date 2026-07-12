import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ObjectsService, OperationalObject } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { PostmortemPanelComponent } from './postmortem-panel.component';

const POSTMORTEM = {
    commander: 'alice',
    incidentDate: '2026-07-10',
    downtime: '1 hour',
    businessImpact: 'batch SLA missed',
    timeline: [{ time: '12:00 UTC', text: 'alert fired' }],
    fiveWhys: ['parser rejected records', 'schema drift', '', '', ''],
    actions: [{ done: false, text: 'pin schema version', owner: 'ops', due: '2026-07-20' }],
};

const INCIDENT: OperationalObject = {
    id: 'i1',
    objectType: 'INCIDENT',
    title: 'Late feed',
    description: 'rejected-file spike',
    status: 'DIAGNOSING',
    priority: 'MAJOR',
    assignee: 'operator',
    attributes: {
        category: 'Data Quality / Timeliness / Late arrival',
        tags: 'urgent',
        postmortem: JSON.stringify(POSTMORTEM),
    },
    createdAt: 1,
    updatedAt: 1,
    closedAt: 0,
};

function create(object: OperationalObject = INCIDENT) {
    const api = { update: vi.fn(() => of(object)) };
    const toastr = { success: vi.fn(), error: vi.fn() };
    const confirm = { confirm: vi.fn(() => Promise.resolve(true)) };
    TestBed.configureTestingModule({
        imports: [PostmortemPanelComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ObjectsService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoConfirmService, useValue: confirm },
        ],
    });
    const fixture = TestBed.createComponent(PostmortemPanelComponent);
    fixture.componentRef.setInput('object', object);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, api, toastr };
}

describe('PostmortemPanelComponent', () => {
    it('populates the postmortem template from attributes.postmortem', () => {
        const { c } = create();
        expect(c.form.controls.commander.value).toBe('alice');
        expect(c.timeline.length).toBe(1);
        expect(c.fiveWhys.length).toBe(5); // always padded to 5 whys
        expect(c.actionsArr.length).toBe(1);
    });

    it('saves the edited postmortem as an attributes patch', () => {
        const { c, api } = create();
        c.form.controls.downtime.setValue('2 hours');
        c.form.markAsDirty();
        c.save();
        expect(api.update).toHaveBeenCalledTimes(1);
        const [id, patch] = (api.update as ReturnType<typeof vi.fn>).mock.calls[0];
        expect(id).toBe('i1');
        const saved = JSON.parse(patch.attributes.postmortem);
        expect(saved.downtime).toBe('2 hours');
        expect(saved.commander).toBe('alice');
        expect(saved.fiveWhys).toHaveLength(5);
    });

    it('offers the lifecycle quick actions for the object status', () => {
        const { fixture, c } = create();
        expect(c.quickActions.map((a) => a.id)).toEqual(['resolve', 'archive', 'escalate']);
        fixture.componentRef.setInput('object', { ...INCIDENT, status: 'IDENTIFIED' });
        fixture.detectChanges();
        expect(c.quickActions.map((a) => a.id)).toContain('accept');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
