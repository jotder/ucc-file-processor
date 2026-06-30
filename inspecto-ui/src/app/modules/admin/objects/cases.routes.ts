import { Routes } from '@angular/router';
import { ObjectDetailComponent } from 'app/modules/admin/objects/object-detail.component';
import { ObjectsComponent } from 'app/modules/admin/objects/objects.component';

export default [
    {
        path: '',
        component: ObjectsComponent,
        data: {
            type: 'CASE',
            title: 'Cases',
            subtitle: 'Investigations — correlate alerts & incidents and track them to resolution',
        },
    },
    { path: ':id', component: ObjectDetailComponent },
] as Routes;
