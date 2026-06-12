import { UpperCasePipe } from '@angular/common';
import { Component, OnInit, ViewEncapsulation, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { ToastrService } from 'ngx-toastr';
import {
    AssistService,
    AssistSettings,
    AssistSettingsTest,
    AssistSettingsUpdate,
} from 'app/inspecto/api';

/** Display names for the provider dropdown. */
const PROVIDER_LABELS: Record<string, string> = {
    anthropic: 'Anthropic Claude',
    openai: 'OpenAI ChatGPT',
    gemini: 'Google Gemini',
    grok: 'xAI Grok',
    ollama: 'Ollama (local)',
    llamacpp: 'llama.cpp server (local)',
};

const TIERS = ['small', 'medium', 'large'] as const;

/**
 * Model Settings — configure the assist agent's model provider (v4.1): hosted (Claude / ChatGPT /
 * Gemini / Grok) or local (Ollama / llama.cpp), per-tier model ids, and the credential reference.
 * Backed by GET/POST /assist/settings + POST /assist/settings/test. API keys are write-only: the
 * server keeps them in memory (or resolves them from the env var named by the key reference) and
 * never echoes them back.
 */
@Component({
    selector: 'app-model-settings',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        UpperCasePipe,
    ],
    templateUrl: './model-settings.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class ModelSettingsComponent implements OnInit {
    private api = inject(AssistService);
    private toastr = inject(ToastrService);

    readonly tiers = TIERS;

    loading = false;
    saving = false;
    testing = false;
    settings: AssistSettings | null = null;
    testResult: AssistSettingsTest | null = null;

    // Form state
    provider = 'ollama';
    baseUrl = '';
    apiKeyRef = '';
    apiKey = '';
    showKey = false;
    models: Record<string, string> = { small: '', medium: '', large: '' };
    timeoutSeconds = 60;

    ngOnInit(): void {
        this.load();
    }

    label(p: string): string {
        return PROVIDER_LABELS[p] ?? p;
    }

    get hostedProviders(): string[] {
        return (this.settings?.knownProviders ?? []).filter((p) => !this.isLocal(p));
    }

    get localProviders(): string[] {
        return (this.settings?.knownProviders ?? []).filter((p) => this.isLocal(p));
    }

    isLocal(p: string): boolean {
        return this.settings?.defaults?.[p]?.local ?? (p === 'ollama' || p === 'llamacpp');
    }

    isSelectable(p: string): boolean {
        return (this.settings?.availableProviders ?? []).includes(p);
    }

    get hostedJarMissing(): boolean {
        return !!this.settings && this.hostedProviders.some((p) => !this.isSelectable(p));
    }

    get currentIsLocal(): boolean {
        return this.isLocal(this.provider);
    }

    /** Seed the form from a provider's defaults (called on dropdown change). */
    onProviderChange(p: string): void {
        const d = this.settings?.defaults?.[p];
        this.provider = p;
        this.baseUrl = d?.baseUrl ?? '';
        this.apiKeyRef = d?.apiKeyRef ?? '';
        this.models = { small: '', medium: '', large: '', ...(d?.models ?? {}) };
        this.apiKey = '';
        this.testResult = null;
    }

    load(): void {
        this.loading = true;
        this.api.settings().subscribe({
            next: (s) => {
                this.settings = s;
                this.loading = false;
                if (!s.supported) return;
                this.provider = s.provider || 'ollama';
                this.baseUrl = s.baseUrl ?? this.settings?.defaults?.[this.provider]?.baseUrl ?? '';
                this.apiKeyRef = s.apiKeyRef ?? '';
                this.models = { small: '', medium: '', large: '', ...(s.models ?? {}) };
                this.timeoutSeconds = s.timeoutSeconds ?? 60;
            },
            error: () => {
                this.loading = false;
                this.settings = { supported: false };
                this.toastr.warning('Could not load assist settings — is ControlApi running?');
            },
        });
    }

    save(): void {
        const update: AssistSettingsUpdate = {
            provider: this.provider,
            baseUrl: this.baseUrl || undefined,
            apiKeyRef: this.apiKeyRef || undefined,
            apiKey: this.apiKey || undefined,
            models: this.models,
            timeoutSeconds: this.timeoutSeconds,
        };
        this.saving = true;
        this.api.saveSettings(update).subscribe({
            next: (s) => {
                this.settings = s;
                this.saving = false;
                this.apiKey = '';
                this.testResult = null;
                this.toastr.success(`Model provider set to ${this.label(this.provider)}`);
            },
            error: (e) => {
                this.saving = false;
                this.toastr.error(e?.error?.error ?? 'Save failed — check the control token scope (assist.write)');
            },
        });
    }

    test(): void {
        this.testing = true;
        this.testResult = null;
        this.api.testSettings().subscribe({
            next: (r) => {
                this.testing = false;
                this.testResult = r;
                const all = TIERS.map((t) => r[t]).filter(Boolean);
                if (all.length && all.every((x) => x!.ok)) this.toastr.success('All tiers responded');
                else this.toastr.warning('Some tiers failed — see results below');
            },
            error: (e) => {
                this.testing = false;
                this.toastr.error(e?.error?.error ?? 'Test failed — check the control token scope (assist.write)');
            },
        });
    }
}
