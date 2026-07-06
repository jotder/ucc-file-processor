import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { SpacesService } from 'app/inspecto/api';
import { MenuService } from './menu.service';

/** A writable stand-in for SpacesService's `currentSpaceId` signal so we can switch spaces in a test. */
const spaceId = signal<string | null>('s1');

function configure(): void {
    TestBed.configureTestingModule({
        providers: [{ provide: SpacesService, useValue: { currentSpaceId: spaceId } }],
    });
}

describe('MenuService', () => {
    beforeEach(() => {
        localStorage.clear();
        spaceId.set('s1');
        configure();
    });

    it('starts empty and persists a mutation across service instances', () => {
        const svc = TestBed.inject(MenuService);
        expect(svc.nodes()).toEqual([]);
        const id = svc.mutate((s) => s.addMenu('Revenue'));
        expect(svc.nodes().map((n) => n.title)).toEqual(['Revenue']);

        // a fresh DI scope restores from localStorage
        TestBed.resetTestingModule();
        configure();
        const svc2 = TestBed.inject(MenuService);
        expect(svc2.nodes().map((n) => n.title)).toEqual(['Revenue']);
        expect(svc2.tree().nodes[0].id).toBe(id);
    });

    it('scopes the tree per space', () => {
        const svc = TestBed.inject(MenuService);
        svc.mutate((s) => s.addMenu('Revenue'));

        spaceId.set('s2');
        expect(svc.nodes()).toEqual([]); // s2 has its own (empty) tree
        svc.mutate((s) => s.addMenu('FMS'));
        expect(svc.nodes().map((n) => n.title)).toEqual(['FMS']);

        spaceId.set('s1');
        expect(svc.nodes().map((n) => n.title)).toEqual(['Revenue']);
    });
});
