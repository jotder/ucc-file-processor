import { Routes } from '@angular/router';
import { ObjectDetailComponent } from 'app/modules/admin/objects/object-detail.component';
import { ObjectMailComponent } from 'app/modules/admin/objects/object-mail.component';

export default [
    {
        path: '',
        component: ObjectMailComponent,
        data: {
            type: 'INCIDENT',
            title: 'Incidents',
            subtitle: 'Operator-managed problems — lifecycle, ownership and SLA tracking',
        },
    },
    { path: ':id', component: ObjectDetailComponent },
] as Routes;
