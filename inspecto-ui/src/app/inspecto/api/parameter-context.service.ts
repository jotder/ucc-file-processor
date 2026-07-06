import { Injectable, inject } from '@angular/core';
import { ParameterContext } from 'app/inspecto/query';
import { LensService } from './lens.service';

/**
 * The resolution seam for the R3 `$`-parameter namespace (`inspecto/query/parameters.ts`): builds a
 * {@link ParameterContext} from the session/clock. `$today`/`$now`/`$day(-N)` come from the clock;
 * `$current_user`/`$role` come from the active persona lens today (the app is auth-free — GLOSSARY §1-A),
 * and re-bind to the authenticated subject once the security module lands, with no call-site change.
 * Later providers (scheduler, previous-job output, AI decision) merge into the same context.
 */
@Injectable({ providedIn: 'root' })
export class ParameterContextService {
    private lens = inject(LensService);

    context(): ParameterContext {
        const lens = this.lens.currentLens();
        return { now: new Date(), user: lens, role: lens };
    }
}
