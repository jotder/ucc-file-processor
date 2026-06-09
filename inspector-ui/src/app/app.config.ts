import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter, withHashLocation } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { routes } from './app.routes';
import {
    AppInfoService,
    AuthGuardService,
    AuthService,
    ScreenService,
} from './shared/services';
import { authInterceptor, errorInterceptor } from './shared/api';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withHashLocation()),
    provideHttpClient(withInterceptors([authInterceptor, errorInterceptor])),
    AuthGuardService,
    AuthService,
    ScreenService,
    AppInfoService,
  ]
};
