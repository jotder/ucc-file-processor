import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { ObjectsService, OperationalObject } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { TagDialog } from './tag.dialog';

function obj(id: string, tags?: string): OperationalObject {
    return {
        id,
        objectType: 'INCIDENT',
        title: id,
        description: '',
        status: 'DIAGNOSING',
        attributes: tags ? { tags } : {},
        createdAt: 1,
        updatedAt: 1,
        closedAt: 0,
    };
}

function create(targets: OperationalObject[]) {
    const ref = { close: vi.fn() };
    const api = { createTag: vi.fn((name: string) => of({ name, createdAt: 9 })) };
    TestBed.configureTestingModule({
        imports: [TagDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { targets, registry: [{ name: 'billing', createdAt: 1 }, { name: 'urgent', createdAt: 1 }] } },
            { provide: MatDialogRef, useValue: ref },
            { provide: ObjectsService, useValue: api },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(TagDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, api };
}

describe('TagDialog', () => {
    it('derives the tri-state from the selection (all / some / none)', () => {
        const { c } = create([obj('a', 'urgent,network'), obj('b', 'urgent')]);
        expect(c.stateOf('urgent')).toBe('all');
        expect(c.stateOf('network')).toBe('some'); // in use but only on one target
        expect(c.stateOf('billing')).toBe('none');
        // non-registry tag found on the data still shows up
        expect(c.tags().map((t) => t.name)).toContain('network');
    });

    it('closes with only the touched changes (add + remove)', () => {
        const { c, ref } = create([obj('a', 'urgent,network'), obj('b', 'urgent')]);
        c.toggle('billing', true); // none → add
        c.toggle('urgent', false); // all → remove
        c.toggle('network', true); // some → add to all
        c.apply();
        expect(ref.close).toHaveBeenCalledWith({ add: ['billing', 'network'], remove: ['urgent'] });
    });

    it('creating a tag inline registers it, checks it, and enables Apply', () => {
        const { c, api } = create([obj('a')]);
        c.newTag.setValue('feeds');
        c.createTag();
        expect(api.createTag).toHaveBeenCalledWith('feeds');
        expect(c.tags().map((t) => t.name)).toContain('feeds');
        expect(c.stateOf('feeds')).toBe('all'); // auto-checked: creating here means "apply it"
        expect(c.dirty()).toBe(true);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create([obj('a', 'urgent')]);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
