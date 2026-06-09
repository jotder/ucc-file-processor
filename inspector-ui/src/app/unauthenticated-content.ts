import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { RouterModule } from '@angular/router';
import { SingleCardComponent } from './layouts';
import { Router } from '@angular/router';

@Component({
  selector: 'app-unauthenticated-content',
  template: `
    <app-single-card [title]="title" [description]="description">
      <router-outlet></router-outlet>
    </app-single-card>
  `,
  styles: [`
    :host {
      width: 100%;
      height: 100%;
    }
  `],
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    SingleCardComponent,
  ]
})
export class UnauthenticatedContentComponent {

  constructor(private router: Router) { }

  get title() {
    const path = this.router.url.split('/')[1];
    switch (path) {
      case 'connect': return 'Connect to Inspector';
      default: return 'Inspector';
    }
  }

  get description() {
    const path = this.router.url.split('/')[1];
    switch (path) {
      case 'connect': return 'Enter your operator token to access the console.';
      default: return '';
    }
  }
}
