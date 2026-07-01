import { Routes } from '@angular/router';
import { CatalogComponent } from 'app/modules/admin/catalog/catalog.component';
// Side-effect: register Studio's ComponentKinds on the unified component model when Catalog loads.
import 'app/modules/admin/studio/datasets/dataset.kind';

export default [
    {
        path: '',
        component: CatalogComponent,
    },
    // Datasets are a data asset (Catalog's home); component files stay under studio/datasets/.
    { path: 'datasets', loadChildren: () => import('app/modules/admin/studio/datasets/datasets.routes') },
] as Routes;
