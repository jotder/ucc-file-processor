import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
    AppCommand,
    allCommands,
    clearCommands,
    registerCommand,
    restoreCommands,
    snapshotCommands,
} from './command-registry';
import './app-commands';

// Captured right after the side-effect import, before any spec mutates the shared registry.
const BUILTINS = snapshotCommands();

describe('command-registry', () => {
    let snapshot: AppCommand[];

    beforeEach(() => {
        snapshot = snapshotCommands();
        clearCommands();
    });

    afterEach(() => restoreCommands(snapshot));

    it('registers commands and lists them', () => {
        registerCommand({ title: 'New widget', group: 'Create', link: '/widgets', queryParams: { create: 1 } });
        expect(allCommands().map((c) => c.title)).toEqual(['New widget']);
    });

    it('throws on a duplicate title so collisions surface at startup', () => {
        registerCommand({ title: 'New widget', link: '/widgets' });
        expect(() => registerCommand({ title: 'New widget', link: '/elsewhere' })).toThrowError(/Duplicate/);
    });
});

describe('app-commands (built-ins)', () => {
    it('registers the feature create commands with the ?create=1 handshake', () => {
        const byTitle = new Map(BUILTINS.map((c) => [c.title, c]));
        for (const [title, link] of [
            ['New incident', '/incidents'],
            ['New case', '/cases'],
            ['New job', '/jobs'],
        ] as const) {
            const cmd = byTitle.get(title);
            expect(cmd, title).toBeDefined();
            expect(cmd!.link).toBe(link);
            expect(cmd!.queryParams).toEqual({ create: 1 });
        }
    });
});
