import { Routes } from '@angular/router';
import { DatasetsComponent } from './datasets.component';
import { DatasetEditorComponent } from './dataset-editor.component';

export default [
    { path: '', component: DatasetsComponent },
    { path: 'new', component: DatasetEditorComponent },
    { path: ':id', component: DatasetEditorComponent },
] as Routes;
