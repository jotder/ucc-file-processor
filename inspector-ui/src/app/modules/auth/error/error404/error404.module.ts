import { NgModule } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterModule } from '@angular/router';

import { Error404Component } from './error404.component';

const routes = [
    {
        path: '',
        component: Error404Component
    }
];

@NgModule({
    declarations: [
        Error404Component
    ],
    imports: [
        RouterModule.forChild(routes),

        MatIconModule,
    ]
})
export class Error404Module {
}
