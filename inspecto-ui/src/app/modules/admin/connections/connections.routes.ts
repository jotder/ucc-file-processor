import { Routes } from '@angular/router';
import { ConnectionsComponent } from 'app/modules/admin/connections/connections.component';
import { ConnectionWorkbenchComponent } from 'app/modules/admin/connections/connection-workbench.component';

export default [
    {
        path: '',
        component: ConnectionsComponent,
    },
    {
        path: ':id',
        component: ConnectionWorkbenchComponent,
    },
] as Routes;
