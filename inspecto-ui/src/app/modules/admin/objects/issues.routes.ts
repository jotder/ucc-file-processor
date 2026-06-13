import { Routes } from '@angular/router';
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
] as Routes;
