import { AbstractControl, ValidatorFn } from '@angular/forms';

/**
 * Inline duplicate-name guard (house form rule: duplicate name = inline block on create).
 * Attach to a save-form's `name` control; render the `duplicate` error inline.
 */
export function uniqueNameValidator(taken: () => string[]): ValidatorFn {
    return (c: AbstractControl) => {
        const v = String(c.value ?? '').trim().toLowerCase();
        return taken().some((t) => t.trim().toLowerCase() === v) ? { duplicate: true } : null;
    };
}
