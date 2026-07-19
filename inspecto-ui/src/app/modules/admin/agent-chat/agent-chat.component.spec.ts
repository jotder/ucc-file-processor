import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { AgentService, AgentStreamHandlers } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AgentChatComponent } from './agent-chat.component';

/** A scripted AgentService: session opens immediately; askStream replays the given frames. */
function agentMock(script: (handlers: AgentStreamHandlers) => void, openError?: unknown) {
    return {
        openSession: vi.fn(() =>
            openError ? throwError(() => openError) : of({ sessionId: 's-1', openedAt: '2026-07-19T00:00:00Z' }),
        ),
        askStream: vi.fn(async (_id: string, _q: string, handlers: AgentStreamHandlers) => script(handlers)),
    };
}

function create(agent: ReturnType<typeof agentMock>) {
    TestBed.configureTestingModule({
        imports: [AgentChatComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([{ path: 'runs', loadChildren: () => Promise.resolve([]) }]),
            { provide: AgentService, useValue: agent },
            { provide: ToastrService, useValue: { error: vi.fn() } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    const fixture = TestBed.createComponent(AgentChatComponent);
    fixture.detectChanges();
    return fixture;
}

describe('AgentChatComponent', () => {
    it('opens one session on first send, streams tokens into the agent bubble, and mounts the artifact', async () => {
        const agent = agentMock((h) => {
            h.onToken('Loaded ');
            h.onToken('42 rows.');
            h.onArtifact({ kind: 'kpi', config: { value: 42, label: 'Rows' } });
            h.onComplete({
                kind: 'INLINE_ARTIFACT',
                text: 'Loaded 42 rows.',
                citations: [{ source: 'runs', locator: 'r-9' }],
                navigationTarget: null,
                artifact: { kind: 'kpi', config: { value: 42, label: 'Rows' } },
            });
        });
        const fixture = create(agent);
        const c = fixture.componentInstance;

        c.question.setValue('how many rows loaded?');
        c.send();
        await fixture.whenStable();
        fixture.detectChanges();

        expect(agent.openSession).toHaveBeenCalledTimes(1);
        expect(agent.askStream).toHaveBeenCalledWith('s-1', 'how many rows loaded?', expect.anything(), expect.anything());
        const el = fixture.nativeElement as HTMLElement;
        expect(el.textContent).toContain('how many rows loaded?');
        expect(el.textContent).toContain('Loaded 42 rows.');
        expect(el.querySelector('inspecto-a2ui-render inspecto-kpi')).toBeTruthy(); // the artifact mounted the render host
        expect(el.textContent).toContain('runs (r-9)'); // citation, modestly
        expect(c.busy()).toBe(false);

        // Second send reuses the session — no second open.
        c.question.setValue('and now?');
        c.send();
        await fixture.whenStable();
        expect(agent.openSession).toHaveBeenCalledTimes(1);
        expect(agent.askStream).toHaveBeenCalledTimes(2);
    });

    it('offers a validated navigate button for a NAVIGATION answer and drops a bogus target', async () => {
        const agent = agentMock((h) => {
            h.onToken('See the runs pane.');
            h.onComplete({ kind: 'NAVIGATION', text: 'See the runs pane.', citations: [], navigationTarget: 'runs', artifact: null });
        });
        const fixture = create(agent);
        const c = fixture.componentInstance;
        c.question.setValue('where do I see runs?');
        c.send();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(c.messages().at(-1)?.navigationTarget).toBe('/runs'); // normalized + validated

        // A target that resolves to no configured route is dropped (fail closed).
        c.question.setValue('and something bogus?');
        agent.askStream.mockImplementationOnce(async (_id, _q, h: AgentStreamHandlers) =>
            h.onComplete({ kind: 'NAVIGATION', text: 'Go.', citations: [], navigationTarget: 'no-such-route', artifact: null }),
        );
        c.send();
        await fixture.whenStable();
        expect(c.messages().at(-1)?.navigationTarget).toBeUndefined();
    });

    it('shows an inline error bubble on an error frame', async () => {
        const agent = agentMock((h) => h.onError('unknown session'));
        const fixture = create(agent);
        const c = fixture.componentInstance;
        c.question.setValue('boom');
        c.send();
        await fixture.whenStable();
        fixture.detectChanges();
        const last = c.messages().at(-1);
        expect(last?.error).toBe(true);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('unknown session');
        expect(c.busy()).toBe(false);
    });

    it('degrades to the "agent unavailable" empty state when session-open answers 503', async () => {
        const agent = agentMock(() => void 0, { status: 503 });
        const fixture = create(agent);
        const c = fixture.componentInstance;
        c.question.setValue('anyone home?');
        c.send();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(c.unavailable()).toBe(true);
        expect(agent.askStream).not.toHaveBeenCalled();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Agent unavailable');
    });

    it('renders with no a11y violations', async () => {
        const fixture = create(agentMock(() => void 0));
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
