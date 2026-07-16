import { Params } from '@angular/router';

/**
 * A feature-scoped command palette entry (ui-design-review R3 follow-up). Declarative — a router
 * link plus optional query params, no closures — so the shell layout can offer feature actions
 * (New incident, New job, …) without importing any feature module. The target pane implements the
 * query-param handshake (convention: `?create=1` opens its create dialog and strips the param).
 */
export interface AppCommand {
    title: string;
    /** Icon shown in the palette row (`heroicons_outline:…`). */
    icon?: string;
    /** Palette section header the command lists under. */
    group?: string;
    /** Router link the command navigates to. */
    link: string;
    /** Query params the target pane interprets (e.g. `{ create: 1 }`). */
    queryParams?: Params;
}

/**
 * Module-level registry of {@link AppCommand}s (same lifecycle as `registerViz` / `registerKind`).
 * Commands register via a side-effect import at app start ({@link ./app-commands}); the classic
 * layout merges them into the palette's shell commands.
 */
const COMMANDS = new Map<string, AppCommand>();

/** Register a command. Throws on a duplicate title so collisions surface at startup, not silently. */
export function registerCommand(command: AppCommand): void {
    if (COMMANDS.has(command.title)) {
        throw new Error(`Duplicate AppCommand '${command.title}'`);
    }
    COMMANDS.set(command.title, command);
}

export function allCommands(): AppCommand[] {
    return [...COMMANDS.values()];
}

/** Test-only: reset the registry between specs. */
export function clearCommands(): void {
    COMMANDS.clear();
}

/** Test-only: capture the current command set so a spec can restore it after mutating the shared
 *  (per-worker) registry. Pair with {@link restoreCommands} to keep the suite order-independent. */
export function snapshotCommands(): AppCommand[] {
    return [...COMMANDS.values()];
}

/** Test-only: restore a {@link snapshotCommands} result, replacing the current contents. */
export function restoreCommands(commands: AppCommand[]): void {
    COMMANDS.clear();
    for (const c of commands) COMMANDS.set(c.title, c);
}
