
import { Component, OnInit, inject } from '@angular/core';
import { DxDataGridModule } from 'devextreme-angular/ui/data-grid';
import { DxButtonModule } from 'devextreme-angular/ui/button';
import { DxTabsModule } from 'devextreme-angular/ui/tabs';
import { DxTextBoxModule } from 'devextreme-angular/ui/text-box';
import { DxNumberBoxModule } from 'devextreme-angular/ui/number-box';
import { DxCheckBoxModule } from 'devextreme-angular/ui/check-box';
import { DxSelectBoxModule } from 'devextreme-angular/ui/select-box';
import { DxTagBoxModule } from 'devextreme-angular/ui/tag-box';
import { DxLoadIndicatorModule } from 'devextreme-angular/ui/load-indicator';
import notify from 'devextreme/ui/notify';
import { ConfigService, ConfigSpec, ConfigType, FieldSpec, ValidateResult } from '../../shared/api';

const CONFIG_TYPES: ConfigType[] = ['pipeline', 'enrichment', 'job', 'schema', 'meta'];

/**
 * Config authoring — spec-driven. Picking a type loads its field/rule spec (GET /config/spec/{type})
 * and renders a dynamic form; "Validate draft" posts the assembled config to POST /validate and shows
 * the findings. A second mode validates a saved .toon file by path. No write-to-disk endpoint exists
 * today, so authoring is draft → validate → copy the previewed config (the operator commits the file).
 */
@Component({
  standalone: true,
  imports: [
    DxDataGridModule,
    DxButtonModule,
    DxTabsModule,
    DxTextBoxModule,
    DxNumberBoxModule,
    DxCheckBoxModule,
    DxSelectBoxModule,
    DxTagBoxModule,
    DxLoadIndicatorModule
],
  templateUrl: './config.component.html',
  styleUrls: ['./config.component.scss'],
})
export class ConfigComponent implements OnInit {
  private api = inject(ConfigService);

  modes = [
    { id: 'draft', text: 'Author draft', icon: 'edit' },
    { id: 'file', text: 'Validate file', icon: 'file' },
  ];
  modeIndex = 0;
  get mode(): 'draft' | 'file' { return this.modeIndex === 0 ? 'draft' : 'file'; }

  types = CONFIG_TYPES;
  type: ConfigType = 'pipeline';
  spec: ConfigSpec | null = null;
  specLoading = false;

  /** flat model keyed by FieldSpec.path (dotted); assembled into nested config on validate.
   *  Typed `any` so the dynamic dx editors can two-way bind heterogeneous field values. */
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  model: Record<string, any> = {};
  safety = false;

  configPath = '';

  result: ValidateResult | null = null;
  validating = false;

  ngOnInit(): void { this.loadSpec(); }

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
      error: () => { this.spec = null; this.specLoading = false; },
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

  setVal(path: string, v: unknown): void { this.model[path] = v; }

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
    try { return JSON.stringify(this.assemble(), null, 2); } catch { return '{}'; }
  }

  validateDraft(): void {
    this.validating = true;
    this.result = null;
    this.api.validateDraft(this.type, this.assemble(), this.safety).subscribe({
      next: (r) => { this.result = r; this.validating = false; },
      error: () => { this.validating = false; notify('Validation failed', 'error', 2500); },
    });
  }

  validateFile(): void {
    const path = this.configPath.trim();
    if (!path) { notify('Enter a config path', 'warning', 2000); return; }
    this.validating = true;
    this.result = null;
    this.api.validateFile(path).subscribe({
      next: (r) => { this.result = r; this.validating = false; },
      error: () => { this.validating = false; notify('Validation failed', 'error', 2500); },
    });
  }

  copyPreview(): void {
    navigator.clipboard?.writeText(this.assembledPreview).then(
      () => notify('Config copied to clipboard', 'success', 1800),
      () => notify('Clipboard unavailable', 'warning', 1800),
    );
  }
}
