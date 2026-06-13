import { Routes } from '@angular/router';
import { ObjectDetailComponent } from 'app/modules/admin/objects/object-detail.component';
import { ObjectsComponent } from 'app/modules/admin/objects/objects.component';

export default [
    {
        path: '',
        component: ObjectsComponent,
        data: {
            type: 'ISSUE',
            title: 'Issues',
            subtitle: 'Operator-managed problems — lifecycle, ownership and SLA tracking',
        },
    },
    { path: ':id', component: ObjectDetailComponent },
] as Routes;
