import { Routes } from '@angular/router';
import { ObjectsComponent } from 'app/modules/admin/objects/objects.component';

export default [
    {
        path: '',
        component: ObjectsComponent,
        data: {
            type: 'CASE',
            title: 'Cases',
            subtitle: 'Investigations — correlate alerts & issues and track them to resolution',
        },
    },
] as Routes;
