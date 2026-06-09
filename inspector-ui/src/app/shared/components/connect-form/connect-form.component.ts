import { CommonModule } from '@angular/common';
import { Component, inject } from '@angular/core';
import { DxFormModule } from 'devextreme-angular/ui/form';
import { DxButtonModule } from 'devextreme-angular/ui/button';
import notify from 'devextreme/ui/notify';
import { AppInfoService, AuthService } from '../../services';

/**
 * Connect screen — Inspector's "login". The operator pastes the scoped bearer token(s) configured
 * on the server; there is no username/password. A control token unlocks the full console; an assist
 * token alone enables the read-only catalog + AI assist routes.
 */
@Component({
  selector: 'app-connect-form',
  templateUrl: './connect-form.component.html',
  styleUrls: ['./connect-form.component.scss'],
  standalone: true,
  imports: [CommonModule, DxFormModule, DxButtonModule],
})
export class ConnectFormComponent {
  private authService = inject(AuthService);
  appInfo = inject(AppInfoService);

  loading = false;
  formData: { controlToken?: string; assistToken?: string } = {};

  async onSubmit(e: Event) {
    e.preventDefault();
    this.loading = true;
    const result = await this.authService.connect(
      this.formData.controlToken ?? null,
      this.formData.assistToken ?? null,
    );
    if (!result.isOk) {
      this.loading = false;
      notify(result.message, 'error', 2500);
    }
  }
}
