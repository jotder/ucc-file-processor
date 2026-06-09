import { Component } from '@angular/core';

import { DxButtonModule } from 'devextreme-angular';
import { ThemeService } from '../../services/theme.service';

@Component({
  selector: 'theme-switcher',
  template: `
    <dx-button
      class="theme-button"
      stylingMode="text"
      [icon]="themeService.currentTheme === 'dark' ? 'sun' : 'moon'"
      (onClick)="onButtonClick()"
    ></dx-button>`,
  styleUrls: [],
  standalone: true,
  imports: [DxButtonModule],
})
export class ThemeSwitcherComponent {
  constructor(public themeService: ThemeService) {}

  onButtonClick () {
    this.themeService.switchTheme();
  }
}
