import { Routes } from '@angular/router';
import { ObjectDetailComponent } from 'app/modules/admin/objects/object-detail.component';
import { ObjectMailComponent } from 'app/modules/admin/objects/object-mail.component';

export default [
    {
        path: '',
        component: ObjectMailComponent,
        data: {
            type: 'CASE',
            title: 'Case Manager',
            subtitle: 'Investigations — correlate alerts & incidents and track them to resolution',
        },
    },
    { path: ':id', component: ObjectDetailComponent },
] as Routes;
