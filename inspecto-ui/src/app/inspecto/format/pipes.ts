import { Pipe, PipeTransform } from '@angular/core';
import { fmtPercent } from './format';

/**
 * `{{ ratio | fmtPercent }}` → "1.2%" for a ratio in 0–1 (the error-rate displays). Pure standalone pipe; the
 * app had no formatting pipes before P4. Null/undefined render as an em-dash. Add more pipes here only when a
 * formatter gains a template consumer (adoption-plan STOP — don't pipe-ify everything).
 */
@Pipe({ name: 'fmtPercent', standalone: true })
export class FmtPercentPipe implements PipeTransform {
    transform(ratio: number | null | undefined): string {
        return ratio == null ? '—' : fmtPercent(ratio);
    }
}
