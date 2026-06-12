import { Component, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { AssistIntent } from 'app/ucc/api';
import { AssistPanelComponent } from 'app/ucc/components/assist-panel.component';

interface IntentMeta {
    id: AssistIntent;
    label: string;
    placeholder: string;
}

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
 * AssistPanel (ported from inspector-ui). Selecting an intent re-keys the panel (so its state
 * resets) with the right placeholder. Degrades gracefully when the agent is absent (503).
 */
@Component({
    selector: 'app-assist',
    standalone: true,
    imports: [FormsModule, MatFormFieldModule, MatSelectModule, AssistPanelComponent],
    template: `
        <div class="flex min-w-0 flex-auto flex-col">
            <div class="bg-card flex flex-col border-b p-6 sm:flex-row sm:items-center sm:justify-between sm:px-10 sm:py-8 dark:bg-transparent">
                <div>
                    <div class="text-3xl font-extrabold leading-none tracking-tight">Assistant</div>
                    <div class="text-secondary mt-1.5">Draft-only AI assist skills</div>
                </div>
                <mat-form-field class="gamma-mat-dense mt-4 w-72 sm:mt-0" subscriptSizing="dynamic">
                    <mat-label>Assist skill</mat-label>
                    <mat-select [(ngModel)]="selected">
                        @for (m of intents; track m.id) {
                            <mat-option [value]="m">{{ m.label }}</mat-option>
                        }
                    </mat-select>
                </mat-form-field>
            </div>

            <div class="flex flex-auto flex-col p-6 sm:p-10">
                <!-- track by id recreates the panel when the intent changes, resetting its state -->
                @for (s of [selected]; track s.id) {
                    <app-assist-panel [intent]="s.id" [placeholder]="s.placeholder"></app-assist-panel>
                }
            </div>
        </div>
    `,
    encapsulation: ViewEncapsulation.None,
})
export class AssistComponent {
    readonly intents = INTENTS;
    selected: IntentMeta = INTENTS[0];
}
