import { Directive, computed, input, signal } from '@angular/core';

/**
 * **Resizable-pane handle** (ui-design-review R7) — the shared extraction of object-mail's
 * pointer + keyboard divider. Put it on the separator element between two panes; the host binds
 * the controlled pane's width to the exported `width()` signal:
 *
 * ```html
 * <aside [style.width.px]="nav.width()">…</aside>
 * <div inspectoSplit="mail.nav" #nav="inspectoSplit" [min]="170" [max]="420" [defaultWidth]="240"
 *      aria-label="Resize folder panel (arrow keys or drag)"></div>
 * ```
 *
 * The width persists at `inspecto.split.<stateKey>` (a personal device preference — deliberately
 * NOT per-space, unlike grid layout). Drag with the pointer or nudge ±16px with ArrowLeft/Right;
 * `role="separator"` + `aria-valuenow/min/max` ride along. Hosts supply their own `aria-label`
 * (context) and may add classes (e.g. `hidden lg:block` for responsive layouts); collapse behavior
 * stays host-owned. Set `pane="right"` when the controlled pane sits to the RIGHT of the handle
 * (dragging left then widens it).
 */
@Directive({
    selector: '[inspectoSplit]',
    standalone: true,
    exportAs: 'inspectoSplit',
    host: {
        role: 'separator',
        'aria-orientation': 'vertical',
        tabindex: '0',
        class: 'hover:bg-hover w-1.5 shrink-0 cursor-col-resize',
        '[attr.aria-valuenow]': 'width()',
        '[attr.aria-valuemin]': 'min()',
        '[attr.aria-valuemax]': 'max()',
        '(pointerdown)': 'startResize($event)',
        '(keydown)': 'onKeydown($event)',
    },
})
export class InspectoSplitDirective {
    /** Unique pane key — the width persists at `inspecto.split.<stateKey>`. */
    readonly stateKey = input.required<string>({ alias: 'inspectoSplit' });
    readonly min = input(170);
    readonly max = input(560);
    /** Width used until the user first drags. */
    readonly defaultWidth = input(240);
    /** Which side of the handle the controlled pane sits on: `left` (default) widens as the handle
     *  moves right; `right` widens as it moves left. */
    readonly pane = input<'left' | 'right'>('left');

    /** Set once the user resizes this session; otherwise the persisted / default width applies. */
    private readonly override = signal<number | null>(null);

    /** The controlled pane's current width in px — bind `[style.width.px]` (or a CSS var) to this. */
    readonly width = computed(() => {
        const o = this.override();
        if (o !== null) return this.clamp(o);
        const stored = Number(localStorage.getItem(this.storageKey()));
        return this.clamp(stored > 0 ? stored : this.defaultWidth());
    });

    private readonly storageKey = computed(() => `inspecto.split.${this.stateKey()}`);

    startResize(e: PointerEvent): void {
        e.preventDefault();
        const startX = e.clientX;
        const startW = this.width();
        const dir = this.pane() === 'left' ? 1 : -1;
        const move = (ev: PointerEvent): void => this.set(startW + dir * (ev.clientX - startX));
        const up = (): void => {
            window.removeEventListener('pointermove', move);
            window.removeEventListener('pointerup', up);
        };
        window.addEventListener('pointermove', move);
        window.addEventListener('pointerup', up);
    }

    onKeydown(e: KeyboardEvent): void {
        const dir = this.pane() === 'left' ? 1 : -1;
        if (e.key === 'ArrowLeft') this.set(this.width() - 16 * dir);
        if (e.key === 'ArrowRight') this.set(this.width() + 16 * dir);
    }

    private set(px: number): void {
        const w = this.clamp(px);
        this.override.set(w);
        localStorage.setItem(this.storageKey(), String(w));
    }

    private clamp(px: number): number {
        return Math.min(this.max(), Math.max(this.min(), Math.round(px)));
    }
}
