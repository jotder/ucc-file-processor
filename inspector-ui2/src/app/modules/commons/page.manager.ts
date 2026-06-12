import { inject, Injectable } from '@angular/core';
import { SecurityPrincipal } from './security-principal';
import { Observable, of } from 'rxjs';
import { AppComponentService } from 'app/app-component.service';
import { environment } from 'environments/environment';

interface PageMeta {
    pageUrl: string;
    pageName?: string;
    appName?: string;
}

@Injectable({
    providedIn: 'root',
})
export class PageManager {
    private readonly componentService!: AppComponentService;
    private readonly securityPrincipal!: SecurityPrincipal;

    private savedPages: Record<string, PageMeta> = {};
    private activation = false;
    private readonly ignoredPagePrefixes: string[] = [];

    constructor() {
        this.componentService = inject(AppComponentService);
        this.securityPrincipal = inject(SecurityPrincipal);

        if (this.securityPrincipal.isLoggedIn()) {
            this.activation = true;
            this.loadSavedPages();
        }
    }

    set redirectPath(url: string) {
        window.localStorage.setItem('redirectPath', url);
    }

    get redirectPath(): string {
        return window.localStorage.getItem('redirectPath') ?? '';
    }

    saveIfNotExists(pageDetails: Partial<PageMeta>): void {
        if (!this.activation) this.loadSavedPages();

        const pageUrl = pageDetails.pageUrl;
        const pageName = pageDetails.pageName;

        if (!pageUrl || pageUrl.includes('/apps/refresh') || pageUrl in this.savedPages) return;

        const pageMeta: PageMeta = { pageUrl, pageName };

        this.componentService.saveNewAppPage(pageMeta).subscribe({
            next: () => this.loadSavedPages(),
            error: (err) => console.error('Failed to save page:', err),
        });
    }

    getSearchableAppPages(): Observable<PageMeta[]> {
        if (Object.keys(this.savedPages).length > 0) {
            return of(this.appToolbarSearchablePages(this.savedPages));
        }

        return new Observable((observer) => {
            this.componentService.getAppPages().subscribe({
                next: (data: PageMeta[]) => {
                    if (!data?.length) return;
                    this.activation = true;
                    this.savedPages = Object.fromEntries(
                        data.map((entry) => [entry.pageUrl, entry])
                    );
                    observer.next(this.appToolbarSearchablePages(this.savedPages));
                    observer.complete();
                },
                error: (err) => {
                    console.error('Failed to load pages:', err);
                    observer.error(err);
                },
            });
        });
    }

    private loadSavedPages(): void {
        // Reserved for future implementation
    }

    private appToolbarSearchablePages(pageData: Record<string, PageMeta>): PageMeta[] {
        return Object.values(pageData).filter(
            (page) =>
                page.appName === environment.appName &&
                !this.ignoredPagePrefixes.includes(page.pageUrl)
        );
    }
}