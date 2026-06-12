import { Injectable } from '@angular/core';

const CONTROL = 'inspector.control.token';
const ASSIST = 'inspector.assist.token';

/**
 * Holds the operator's scoped bearer tokens in sessionStorage (cleared when the tab closes).
 * The backend has no login endpoint — the operator pastes the token(s) configured on the server
 * (-Dcontrol.token / -Dassist.read.token). CONTROL is the superuser and also satisfies assist.
 */
@Injectable({ providedIn: 'root' })
export class TokenStore {
  get control(): string | null { return sessionStorage.getItem(CONTROL); }
  get assist(): string | null { return sessionStorage.getItem(ASSIST); }

  /** Token to present on API calls: prefer CONTROL (superuser), else the assist token. */
  get bearer(): string | null { return this.control ?? this.assist; }

  get hasAny(): boolean { return !!this.bearer; }

  set(control: string | null, assist: string | null): void {
    this.write(CONTROL, control);
    this.write(ASSIST, assist);
  }

  clear(): void {
    sessionStorage.removeItem(CONTROL);
    sessionStorage.removeItem(ASSIST);
  }

  private write(key: string, value: string | null): void {
    if (value && value.trim()) sessionStorage.setItem(key, value.trim());
    else sessionStorage.removeItem(key);
  }
}
