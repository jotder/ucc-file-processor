import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { TokenStore } from './token-store.service';

/** Attach the operator's bearer token to every API request (public probes simply ignore it). */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const token = inject(TokenStore).bearer;
  return token
    ? next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }))
    : next(req);
};
