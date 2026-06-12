import { HttpBackend, HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import {
    ApplicationConfig,
    inject,
    isDevMode,
    provideAppInitializer,
    provideZoneChangeDetection,
} from '@angular/core';
import { LuxonDateAdapter } from '@angular/material-luxon-adapter';
import { DateAdapter, MAT_DATE_FORMATS } from '@angular/material/core';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { provideGamma } from '@gamma';
import { TranslocoService, provideTransloco } from '@jsverse/transloco';
import { appRoutes } from 'app/app.routes';
import { provideIcons } from 'app/core/icons/icons.provider';
import { MockApiService } from 'app/mock-api';
import { provideToastr } from 'ngx-toastr';
import { firstValueFrom } from 'rxjs';
import { TranslocoHttpLoader } from './core/transloco/transloco.http-loader';
import { AUTH_HTTP_CLIENT } from './modules/auth/auth-http-client.token';
import { authInterceptor as uccAuthInterceptor } from './ucc/api/auth.interceptor';
import { errorInterceptor as uccErrorInterceptor } from './ucc/api/error.interceptor';

export const appConfig: ApplicationConfig = {
    providers: [
        // Zone.js change detection (explicit opt-in for Angular 21)
        provideZoneChangeDetection({ eventCoalescing: true }),

        // Main HttpClient — UCC inspector interceptors (X-Api-Token bearer + 401 handling).
        // The template's Pronto-OAuth authInterceptor is not wired: ControlApi uses operator
        // tokens (TokenStore), and the OAuth refresh→logout flow would misfire on our 401s.
        provideHttpClient(
            withInterceptors([uccAuthInterceptor, uccErrorInterceptor])
        ),

        // Interceptor-free HttpClient for AuthService only.
        // Uses HttpBackend directly, which skips the entire interceptor chain.
        {
            provide: AUTH_HTTP_CLIENT,
            useFactory: (backend: HttpBackend) => new HttpClient(backend),
            deps: [HttpBackend],
        },
        
        // Angular 21: use async animations provider for better performance
        provideAnimationsAsync(),
        provideToastr({ positionClass: 'toast-bottom-right' }),
        provideRouter(
            appRoutes,
            withInMemoryScrolling({ scrollPositionRestoration: 'enabled' }),
            withComponentInputBinding()
        ),

        // Material Date Adapter
        {
            provide: DateAdapter,
            useClass: LuxonDateAdapter,
        },
        {
            provide: MAT_DATE_FORMATS,
            useValue: {
                parse: {
                    dateInput: 'D',
                },
                display: {
                    dateInput: 'DDD',
                    monthYearLabel: 'LLL yyyy',
                    dateA11yLabel: 'DD',
                    monthYearA11yLabel: 'LLLL yyyy',
                },
            },
        },

        // Transloco Config
        provideTransloco({
            config: {
                availableLangs: [
                    {
                        id: 'en',
                        label: 'English',
                    },
                    {
                        id: 'tr',
                        label: 'Turkish',
                    },
                ],
                defaultLang: 'en',
                fallbackLang: 'en',
                reRenderOnLangChange: true,
                prodMode: !isDevMode(),
            },
            loader: TranslocoHttpLoader,
        }),
        provideAppInitializer(() => {
            const translocoService = inject(TranslocoService);
            const defaultLang = translocoService.getDefaultLang();
            translocoService.setActiveLang(defaultLang);

            return firstValueFrom(translocoService.load(defaultLang));
        }),

        // Gamma
        // provideAuth(),
        provideIcons(),
        provideGamma({
            mockApi: {
                delay: 0,
                service: MockApiService,
            },
            gamma: {
                layout: 'classic',
                scheme: 'dark',
                screens: {
                    sm: '600px',
                    md: '960px',
                    lg: '1280px',
                    xl: '1440px',
                },
                theme: 'theme-default',
                themes: [
                    {
                        id: 'theme-default',
                        name: 'Default',
                    },
                    {
                        id: 'theme-brand',
                        name: 'Brand',
                    },
                    {
                        id: 'theme-teal',
                        name: 'Teal',
                    },
                    {
                        id: 'theme-rose',
                        name: 'Rose',
                    },
                    {
                        id: 'theme-purple',
                        name: 'Purple',
                    },
                    {
                        id: 'theme-amber',
                        name: 'Amber',
                    },
                ],
            },
        }),
    ],
};