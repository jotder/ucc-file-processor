import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { DxDataGridModule } from 'devextreme-angular/ui/data-grid';
import { DxButtonModule } from 'devextreme-angular/ui/button';
import { DxPopupModule } from 'devextreme-angular/ui/popup';
import { DxNumberBoxModule } from 'devextreme-angular/ui/number-box';
import { DxLoadIndicatorModule } from 'devextreme-angular/ui/load-indicator';
import notify from 'devextreme/ui/notify';
import { AssistPanelComponent } from '../../shared/components';
import { AssistService, Diagnosis } from '../../shared/api';

/**
 * Diagnoses — recent failure root-cause analyses (GET /assist/diagnoses). A detail drawer shows the
 * full root-cause + citations and the suggested alert-rule .toon; "Refine as alert" hands the
 * diagnosis text to the diagnose-and-alert assist flow (embedded AssistPanel) to produce a draft rule.
 */
@Component({
  standalone: true,
  imports: [
    CommonModule, DxDataGridModule, DxButtonModule, DxPopupModule,
    DxNumberBoxModule, DxLoadIndicatorModule, AssistPanelComponent,
  ],
  templateUrl: './diagnoses.component.html',
  styleUrls: ['./diagnoses.component.scss'],
})
export class DiagnosesComponent implements OnInit {
  private api = inject(AssistService);

  diagnoses: Diagnosis[] = [];
  loading = false;
  limit = 50;

  detailVisible = false;
  selected: Diagnosis | null = null;
  alertText = '';

  ngOnInit(): void { this.load(); }

  load(): void {
    this.loading = true;
    this.api.diagnoses(this.limit).subscribe({
      next: (d) => { this.diagnoses = d; this.loading = false; },
      error: () => { this.diagnoses = []; this.loading = false; },
    });
  }

  openDetail = (e: { data: Diagnosis }) => {
    this.selected = e.data;
    this.alertText = `${e.data.rootCause} (pipeline ${e.data.pipeline}, batch ${e.data.batchId})`;
    this.detailVisible = true;
  };

  copy(text?: string | null): void {
    if (!text) return;
    navigator.clipboard?.writeText(text).then(
      () => notify('Copied to clipboard', 'success', 1500),
      () => notify('Clipboard unavailable', 'warning', 1500),
    );
  }
}
