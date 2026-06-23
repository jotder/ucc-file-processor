import { Component, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, ConfigSpec, ConfigType, FieldSpec, ValidateResult } from 'app/inspecto/api';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';

const CONFIG_TYPES: ConfigType[] = ['pipeline', 'enrichment', 'job', 'schema', 'meta'];

/**
 * Config authoring — spec-driven (ported from inspector-ui onto the gamma shell). Picking a type
 * loads its field/rule spec (GET /config/spec/{type}) and renders a dynamic form; "Validate draft"
 * posts the assembled config to POST /validate and shows the findings. A second mode validates a
 * saved .toon file by path. ARRAY fields are edited as comma-separated text (assembled to arrays).
 */
@Component({
    selector: 'app-config',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTabsModule,
        InspectoSkeletonComponent,
        StatusBadgeComponent,
    ],
    templateUrl: './config.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class ConfigComponent implements OnInit {
    private api = inject(ConfigService);
    private toastr = inject(ToastrService);

    modeIndex = 0;
    get mode(): 'draft' | 'file' {
        return this.modeIndex === 0 ? 'draft' : 'file';
    }

    readonly types = CONFIG_TYPES;
    type: ConfigType = 'pipeline';
    spec: ConfigSpec | null = null;
    specLoading = false;

    /** flat model keyed by FieldSpec.path (dotted); assembled into nested config on validate. */
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    model: Record<string, any> = {};
    safety = false;

    configPath = '';

    result: ValidateResult | null = null;
    validating = false;

    ngOnInit(): void {
        this.loadSpec();
    }

    loadSpec(): void {
        this.result = null;
        this.model = {};
        this.specLoading = true;
        this.api.spec(this.type).subscribe({
            next: (s) => {
                this.spec = s;
                for (const f of s.fields) if (f.default !== undefined) this.model[f.path] = f.default;
                this.specLoading = false;
            },
            error: () => {
                this.spec = null;
                this.specLoading = false;
            },
        });
    }

    editorType(f: FieldSpec): 'select' | 'tags' | 'number' | 'boolean' | 'text' {
        if (f.options && f.options.length) return 'select';
        switch (f.type) {
            case 'ARRAY': return 'tags';
            case 'INTEGER': return 'number';
            case 'BOOLEAN': return 'boolean';
            default: return 'text';
        }
    }

    /** ARRAY fields bind to a comma-separated string; normalize to string[] in the model. */
    setTags(path: string, raw: string): void {
        const arr = raw.split(',').map((s) => s.trim()).filter(Boolean);
        this.model[path] = arr.length ? arr : undefined;
    }

    tagsText(path: string): string {
        const v = this.model[path];
        return Array.isArray(v) ? v.join(', ') : '';
    }

    /** Build a nested config object from the flat dotted-path model, dropping empties. */
    private assemble(): Record<string, unknown> {
        const out: Record<string, unknown> = {};
        for (const [path, value] of Object.entries(this.model)) {
            if (value === undefined || value === null || value === '') continue;
            if (Array.isArray(value) && value.length === 0) continue;
            const parts = path.split('.');
            let cur = out;
            for (let i = 0; i < parts.length - 1; i++) {
                cur[parts[i]] = cur[parts[i]] || {};
                cur = cur[parts[i]] as Record<string, unknown>;
            }
            cur[parts[parts.length - 1]] = value;
        }
        return out;
    }

    get assembledPreview(): string {
        try {
            return JSON.stringify(this.assemble(), null, 2);
        } catch {
            return '{}';
        }
    }

    validateDraft(): void {
        this.validating = true;
        this.result = null;
        this.api.validateDraft(this.type, this.assemble(), this.safety).subscribe({
            next: (r) => {
                this.result = r;
                this.validating = false;
            },
            error: () => {
                this.validating = false;
                this.toastr.error('Validation failed');
            },
        });
    }

    validateFile(): void {
        const path = this.configPath.trim();
        if (!path) {
            this.toastr.warning('Enter a config path');
            return;
        }
        this.validating = true;
        this.result = null;
        this.api.validateFile(path).subscribe({
            next: (r) => {
                this.result = r;
                this.validating = false;
            },
            error: () => {
                this.validating = false;
                this.toastr.error('Validation failed');
            },
        });
    }

    copyPreview(): void {
        navigator.clipboard?.writeText(this.assembledPreview).then(
            () => this.toastr.success('Config copied to clipboard'),
            () => this.toastr.warning('Clipboard unavailable'),
        );
    }
}
