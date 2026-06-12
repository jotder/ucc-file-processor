import { NgModule } from '@angular/core';
import { RouterModule } from '@angular/router';
import { Error500Component } from './error500.component';


const routes = [
    {
        path: '',
        component: Error500Component
    }
];

@NgModule({
    declarations: [
        Error500Component
    ],
    imports: [
        RouterModule.forChild(routes),

    ]
})
export class Error500Module {
}
