import { ChangeDetectionStrategy, Component, DestroyRef, ViewEncapsulation, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Router } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { A2uiArtifact } from 'app/inspecto/a2ui/a2ui-artifact';
import { A2uiRenderComponent } from 'app/inspecto/a2ui/a2ui-render.component';
import { isNavigableTarget } from 'app/inspecto/a2ui/route-validation';
import { AgentAskResult, AgentCitation, AgentService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';

/** One transcript entry. Agent messages grow token-by-token while `streaming`. */
interface ChatMessage {
    role: 'user' | 'agent';
    text: string;
    streaming?: boolean;
    error?: boolean;
    artifact?: A2uiArtifact;
    citations?: AgentCitation[];
    /** Validated in-app route offered as a button when the answer kind is NAVIGATION. */
    navigationTarget?: string;
}

/**
 * Agent chat (S4) — the first streaming surface on the deliberative agent loop. Opens one session
 * lazily on the first send (POST /agent/sessions), then streams every question over SSE
 * (POST …/ask/stream): tokens grow the current agent bubble live (announced via an `aria-live`
 * region), an `artifact` frame mounts `<inspecto-a2ui-render>` inline, and the terminal `complete`
 * result supplies the authoritative text, citations, and — for NAVIGATION answers — a
 * route-validated "Open" button. Sending a new question cancels any in-flight stream (AbortSignal),
 * as does destroying the component. A 503 on session-open degrades to the same "intelligence module
 * absent" empty-state UX the assist panel uses. Sibling of `/assist` (the reflex layer) — this pane
 * hosts the multi-turn deliberative loop.
 */
@Component({
    selector: 'app-agent-chat',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        A2uiRenderComponent,
        InspectoEmptyStateComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './agent-chat.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class AgentChatComponent {
    private api = inject(AgentService);
    private router = inject(Router);
    private toastr = inject(ToastrService);

    readonly messages = signal<ChatMessage[]>([]);
    /** True while a stream (or the session-open preceding it) is in flight. */
    readonly busy = signal(false);
    /** True once session-open answered 503 — the intelligence module is absent on this backend. */
    readonly unavailable = signal(false);
    readonly question = new FormControl('', { nonNullable: true });

    private readonly sessionId = signal<string | null>(null);
    private controller: AbortController | null = null;

    constructor() {
        inject(DestroyRef).onDestroy(() => this.controller?.abort());
    }

    send(): void {
        const q = this.question.value.trim();
        if (!q || this.unavailable()) return;
        this.cancelInFlight();
        const sessionId = this.sessionId();
        if (sessionId) {
            this.stream(sessionId, q);
            return;
        }
        // First send: open the session once, then stream against it.
        this.busy.set(true);
        this.api.openSession(undefined, { route: this.router.url }).subscribe({
            next: (session) => {
                this.sessionId.set(session.sessionId);
                this.stream(session.sessionId, q);
            },
            error: (err) => {
                this.busy.set(false);
                if (err?.status === 503) this.unavailable.set(true);
                else this.toastr.error(apiErrorMessage(err, 'Could not open an agent session.'));
            },
        });
    }

    /** Route-validated navigation for a NAVIGATION answer's target button. */
    goTo(target: string): void {
        void this.router.navigateByUrl(target);
    }

    /** The open agent session id, for `<inspecto-a2ui-render>`'s S6 `invoke` confirm-apply flow to
     *  thread as `X-Agent-Session` — so an apply this chat confirms audits as `agent:<sessionId>`. */
    sessionIdValue(): string | null {
        return this.sessionId();
    }

    private stream(sessionId: string, question: string): void {
        this.question.setValue('');
        this.busy.set(true);
        this.messages.update((list) => [...list, { role: 'user', text: question }, { role: 'agent', text: '', streaming: true }]);
        const controller = new AbortController();
        this.controller = controller;
        void this.api.askStream(
            sessionId,
            question,
            {
                onToken: (token) => this.patchCurrent((m) => ({ ...m, text: m.text + token })),
                onArtifact: (artifact) => this.patchCurrent((m) => ({ ...m, artifact })),
                onComplete: (result) => this.finish(result),
                onError: (message) => {
                    this.patchCurrent((m) => ({ ...m, text: m.text || message, error: true, streaming: false }));
                    this.busy.set(false);
                },
            },
            controller.signal,
        );
    }

    /** The terminal `complete` frame: authoritative text, citations, artifact, navigation offer. */
    private finish(result: AgentAskResult): void {
        this.patchCurrent((m) => ({
            ...m,
            streaming: false,
            text: result.text || m.text,
            error: result.kind === 'ERROR' || m.error,
            artifact: m.artifact ?? result.artifact ?? undefined,
            citations: result.citations?.length ? result.citations : undefined,
            navigationTarget: this.validNavigationTarget(result),
        }));
        this.busy.set(false);
        this.controller = null;
    }

    /** A NAVIGATION answer's target, normalized to an absolute in-app path and validated against the
     *  route config — anything that doesn't resolve is dropped (fail closed, spike §4.4). */
    private validNavigationTarget(result: AgentAskResult): string | undefined {
        if (result.kind !== 'NAVIGATION' || !result.navigationTarget) return undefined;
        const target = result.navigationTarget.startsWith('/') ? result.navigationTarget : `/${result.navigationTarget}`;
        return isNavigableTarget(this.router.config, target) ? target : undefined;
    }

    /** Immutably patch the trailing agent message (the one currently streaming). */
    private patchCurrent(patch: (m: ChatMessage) => ChatMessage): void {
        this.messages.update((list) => {
            const last = list.length - 1;
            if (last < 0 || list[last].role !== 'agent') return list;
            const next = [...list];
            next[last] = patch(next[last]);
            return next;
        });
    }

    /** Abort any in-flight stream and settle its bubble — called before a new send and on destroy. */
    private cancelInFlight(): void {
        if (!this.controller) return;
        this.controller.abort();
        this.controller = null;
        this.patchCurrent((m) => (m.streaming ? { ...m, streaming: false, text: m.text || 'Cancelled.' } : m));
        this.busy.set(false);
    }
}
