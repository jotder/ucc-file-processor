import { HttpEvent, HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { GammaLoadingService } from '@gamma/services/loading/loading.service';
import { Observable, finalize, take } from 'rxjs';

export const gammaLoadingInterceptor = (
    req: HttpRequest<unknown>,
    next: HttpHandlerFn
): Observable<HttpEvent<unknown>> => {
    const gammaLoadingService = inject(GammaLoadingService);
    let handleRequestsAutomatically = false;

    gammaLoadingService.auto$.pipe(take(1)).subscribe((value) => {
        handleRequestsAutomatically = value;
    });

    // If the Auto mode is turned off, do nothing
    if (!handleRequestsAutomatically) {
        return next(req);
    }

    // Set the loading status to true
    gammaLoadingService._setLoadingStatus(true, req.url);

    return next(req).pipe(
        finalize(() => {
            // Set the status to false if there are any errors or the request is completed
            gammaLoadingService._setLoadingStatus(false, req.url);
        })
    );
};
