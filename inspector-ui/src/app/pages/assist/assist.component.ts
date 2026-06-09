import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { DxSelectBoxModule } from 'devextreme-angular/ui/select-box';
import { AssistPanelComponent } from '../../shared/components';
import { ASSIST_INTENTS, AssistIntent } from '../../shared/api';

interface IntentMeta { id: AssistIntent; label: string; placeholder: string; }

const INTENTS: IntentMeta[] = [
  { id: 'kpi-to-sql', label: 'KPI → SQL', placeholder: 'e.g. how many batches per status for MINI_ETL' },
  { id: 'report-sql', label: 'Report → SQL', placeholder: 'e.g. daily output rows by pipeline for the last week' },
  { id: 'nl-to-schedule', label: 'NL → schedule', placeholder: 'e.g. run the events ingest every weekday at 6am' },
  { id: 'suggest-config', label: 'Suggest config', placeholder: 'e.g. a pipeline config for nightly roaming CDRs' },
  { id: 'diagnose-and-alert', label: 'Diagnose → alert', placeholder: 'e.g. warn when the error rate exceeds 5%' },
  { id: 'explain-entity', label: 'Explain entity', placeholder: 'e.g. what is the mini events table and how is it derived' },
  { id: 'report-narrative', label: 'Report narrative', placeholder: 'paste a report summary to narrate' },
];

/**
 * AI assist console — a single screen exposing all seven assist intents via the reusable
 * AssistPanel. Selecting an intent re-keys the panel (so its state resets) with the right
 * placeholder. Degrades gracefully when the agent is absent (503).
 */
@Component({
  standalone: true,
  imports: [CommonModule, DxSelectBoxModule, AssistPanelComponent],
  templateUrl: './assist.component.html',
})
export class AssistComponent {
  intents = INTENTS;
  selected: IntentMeta = INTENTS[0];
  readonly all = ASSIST_INTENTS;

  onIntentChange(e: { value?: IntentMeta }): void {
    if (e.value) this.selected = e.value;
  }

  trackIntent = (_: number, m: IntentMeta) => m.id;
}
