import { of, throwError } from 'rxjs';
import { optimisticMutate } from './optimistic';

describe('optimisticMutate', () => {
    it('applies immediately and reconciles with the server result on success', () => {
        const calls: string[] = [];
        optimisticMutate({
            apply: () => calls.push('apply'),
            commit: of({ paused: true }),
            reconcile: (r) => calls.push(`reconcile:${r.paused}`),
            rollback: () => calls.push('rollback'),
            onError: () => calls.push('onError'),
        });
        expect(calls).toEqual(['apply', 'reconcile:true']);
    });

    it('rolls back and reports the error on failure (no reconcile)', () => {
        const calls: string[] = [];
        const boom = new Error('boom');
        optimisticMutate({
            apply: () => calls.push('apply'),
            commit: throwError(() => boom),
            reconcile: () => calls.push('reconcile'),
            rollback: () => calls.push('rollback'),
            onError: (e) => calls.push(`onError:${(e as Error).message}`),
        });
        expect(calls).toEqual(['apply', 'rollback', 'onError:boom']);
    });

    it('works without optional reconcile/onError', () => {
        const calls: string[] = [];
        optimisticMutate({
            apply: () => calls.push('apply'),
            commit: of(1),
            rollback: () => calls.push('rollback'),
        });
        expect(calls).toEqual(['apply']);
    });
});
