// auth-http-client.token.ts
import { InjectionToken } from '@angular/core';
import { HttpClient } from '@angular/common/http';

/**
 * A dedicated HttpClient instance for AuthService that bypasses
 * the authInterceptor — preventing the NG0200 circular DI error.
 *
 * Usage: inject(AUTH_HTTP_CLIENT) inside AuthService instead of HttpClient.
 */
export const AUTH_HTTP_CLIENT = new InjectionToken<HttpClient>('AUTH_HTTP_CLIENT');