import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from 'app/inspecto/api';
import { MENU_TREE_VERSION, MenuTree } from './menu-types';

/**
 * The real per-space navigation-menus backend (`NavRoutes`, GET/PUT `/nav/menus`) — a singleton
 * document persisted as `nav-menus.toon`, same settings-doc discipline as branding. Space-scoped by
 * the global `spaceInterceptor`; the server stamps `space` on every response and canonicalizes the
 * nodes (whitelist copy, dedupe ids, children-XOR-binding). PUT is gated by `canAuthorWorkbench`
 * (403), the write root (503) and validation (422); GET is open and returns an empty tree when
 * nothing is persisted yet (never 404). The wire shape is exactly {@link MenuTree}.
 */
@Injectable({ providedIn: 'root' })
export class NavMenusService {
    private http = inject(HttpClient);

    get(): Observable<MenuTree> {
        return this.http.get<MenuTree>(apiUrl('/nav/menus'));
    }

    put(tree: MenuTree): Observable<MenuTree> {
        return this.http.put<MenuTree>(apiUrl('/nav/menus'), { version: MENU_TREE_VERSION, nodes: tree.nodes });
    }
}
