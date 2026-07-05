import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { AssistService, AssistSettings } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ModelSettingsComponent } from './model-settings.component';

const SETTINGS: AssistSettings = {
    supported: true,
    provider: 'ollama',
    apiKeyRef: '',
    models: { small: 'llama3.2', medium: 'llama3.2', large: 'llama3.2' },
    timeoutSeconds: 45,
    availableProviders: ['ollama'],
    knownProviders: ['anthropic', 'ollama'],
    defaults: {
        ollama: { baseUrl: 'http://localhost:11434', apiKeyRef: '', models: { small: 'llama3.2' }, local: true },
        anthropic: { baseUrl: '', apiKeyRef: 'ANTHROPIC_API_KEY', models: {}, local: false },
    },
};

function create() {
    const api = { settings: vi.fn(() => of(SETTINGS)) };
    TestBed.configureTestingModule({
        imports: [ModelSettingsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: AssistService, useValue: api },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined, warning: () => undefined } },
        ],
    });
    const fixture = TestBed.createComponent(ModelSettingsComponent);
    fixture.detectChanges(); // ngOnInit → load()
    return { fixture, api };
}

describe('ModelSettingsComponent', () => {
    it('loads the settings on init and seeds the form (base URL from provider defaults)', () => {
        const { fixture, api } = create();
        const c = fixture.componentInstance;
        expect(api.settings).toHaveBeenCalled();
        expect(c.provider).toBe('ollama');
        expect(c.baseUrl).toBe('http://localhost:11434');
        expect(c.timeoutSeconds).toBe(45);
        expect(c.currentIsLocal).toBe(true);
        expect(c.hostedJarMissing).toBe(true); // anthropic known but not available
    });

    it('has no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
