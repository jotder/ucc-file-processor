import { Route, Routes } from '@angular/router';

/**
 * Whether an agent-proposed navigation target resolves to a real configured route (spike §4.4:
 * `navigate` actions are validated against the app's own route config — never external URLs, never
 * `window.open`). Fail-closed: anything that can't be statically matched is rejected.
 *
 * Matching walks the route tree segment by segment. `:param` segments match any value; a
 * `loadChildren` route accepts its remaining segments (the lazy subtree can't be inspected without
 * loading it — the router resolves the remainder at navigation time); custom-matcher routes are
 * rejected (not statically evaluable).
 */
export function isNavigableTarget(routes: Routes, target: unknown): boolean {
    if (typeof target !== 'string') return false;
    const t = target.trim();
    // In-app absolute paths only — no protocol-relative ('//host'), no scheme ('http://…').
    if (!t.startsWith('/') || t.startsWith('//') || t.includes('://')) return false;
    const segments = t.split(/[?#]/)[0].split('/').filter(Boolean);
    if (!segments.length) return false;
    return matchesAny(routes, segments);
}

function matchesAny(routes: Routes, segments: string[]): boolean {
    return routes.some((route) => matches(route, segments));
}

function matches(route: Route, segments: string[]): boolean {
    if (route.path === undefined) return false; // custom matcher — reject (fail closed)
    const parts = route.path.split('/').filter(Boolean);
    if (parts.length === 0) {
        // Componentless '' wrapper (e.g. the guarded LayoutComponent route) — descend with the same segments.
        return route.children ? matchesAny(route.children, segments) : false;
    }
    if (parts.length > segments.length) return false;
    if (!parts.every((p, i) => p.startsWith(':') || p === segments[i])) return false;
    const rest = segments.slice(parts.length);
    if (rest.length === 0) {
        return !!(route.component || route.loadComponent || route.loadChildren || route.children || route.redirectTo !== undefined);
    }
    if (route.loadChildren) return true; // lazy subtree — remainder resolves at navigation time
    return route.children ? matchesAny(route.children, rest) : false;
}
