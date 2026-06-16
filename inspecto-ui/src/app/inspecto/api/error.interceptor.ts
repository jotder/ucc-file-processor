import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { TokenStore } from './token-store.service';

/**
 * When the backend can't be reached at all (connection refused, DNS failure, CORS, network
 * down) Angular's HttpClient reports `status === 0`. We surface a single toast rather than
 * letting every screen invent its own "unreachable" message. De-duped because one page often
 * fires several requests at once (e.g. the dashboard's forkJoin) — without this the user would
 * get a stack of identical toasts.
 */
let lastUnreachableToastAt = 0;
const UNREACHABLE_TOAST_THROTTLE_MS = 5000;

/**
 * Global API error handling. A 401 means the token is missing/invalid → clear it, surface a
 * toast and bounce to the Connect screen. A `status === 0` means the backend is unreachable →
 * one throttled toast. Errors are re-thrown so per-screen handlers can still react (e.g. assist
 * 503).
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const toastr = inject(ToastrService);
  const tokens = inject(TokenStore);
  const router = inject(Router);
  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401) {
        tokens.clear();
        toastr.error('Not authorized — please reconnect with a valid token.');
        router.navigate(['/connect']);
      } else if (err.status === 0) {
        const now = Date.now();
        if (now - lastUnreachableToastAt > UNREACHABLE_TOAST_THROTTLE_MS) {
          lastUnreachableToastAt = now;
          toastr.error('Backend not reachable — check that the service is running.', 'Connection error');
        }
      }
      return throwError(() => err);
    })
  );
};
