import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Router } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';
import { TokenStore } from '../api/token-store.service';

describe('AuthService', () => {
  let auth: AuthService;
  const router = { navigate: vi.fn() };

  beforeEach(() => {
    sessionStorage.clear();
    router.navigate.mockClear();
    TestBed.configureTestingModule({
      providers: [AuthService, TokenStore, { provide: Router, useValue: router }],
    });
    auth = TestBed.inject(AuthService);
  });

  it('is logged out with no tokens', () => {
    expect(auth.loggedIn).toBe(false);
    expect(auth.hasControl()).toBe(false);
    expect(auth.hasAssist()).toBe(false);
  });

  it('rejects connect() with no tokens and does not navigate', async () => {
    const res = await auth.connect('', '   ');
    expect(res.isOk).toBe(false);
    expect(router.navigate).not.toHaveBeenCalled();
    expect(auth.loggedIn).toBe(false);
  });

  it('connect() with a control token logs in and navigates', async () => {
    const res = await auth.connect('ctrl', null);
    expect(res.isOk).toBe(true);
    expect(auth.loggedIn).toBe(true);
    expect(auth.hasControl()).toBe(true);
    expect(router.navigate).toHaveBeenCalledOnce();
  });

  it('an assist-only token grants assist but not control', async () => {
    await auth.connect(null, 'asst');
    expect(auth.hasControl()).toBe(false);
    expect(auth.hasAssist()).toBe(true);
  });

  it('control implies assist', async () => {
    await auth.connect('ctrl', null);
    expect(auth.hasAssist()).toBe(true);
  });

  it('logOut() clears tokens and routes to connect', async () => {
    await auth.connect('ctrl', null);
    await auth.logOut();
    expect(auth.loggedIn).toBe(false);
    expect(router.navigate).toHaveBeenLastCalledWith(['/connect']);
  });
});
