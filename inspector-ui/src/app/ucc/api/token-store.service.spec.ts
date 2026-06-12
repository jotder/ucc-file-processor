import { beforeEach, describe, expect, it } from 'vitest';
import { TokenStore } from './token-store.service';

describe('TokenStore', () => {
  let store: TokenStore;

  beforeEach(() => {
    sessionStorage.clear();
    store = new TokenStore();
  });

  it('starts empty', () => {
    expect(store.control).toBeNull();
    expect(store.assist).toBeNull();
    expect(store.bearer).toBeNull();
    expect(store.hasAny).toBe(false);
  });

  it('stores control and assist tokens', () => {
    store.set('ctrl', 'asst');
    expect(store.control).toBe('ctrl');
    expect(store.assist).toBe('asst');
    expect(store.hasAny).toBe(true);
  });

  it('prefers the control token for the bearer (superuser)', () => {
    store.set('ctrl', 'asst');
    expect(store.bearer).toBe('ctrl');
  });

  it('falls back to the assist token when no control token is held', () => {
    store.set(null, 'asst');
    expect(store.bearer).toBe('asst');
  });

  it('trims tokens and treats blank input as absent', () => {
    store.set('  ctrl  ', '   ');
    expect(store.control).toBe('ctrl');
    expect(store.assist).toBeNull();
  });

  it('clear() removes both tokens', () => {
    store.set('ctrl', 'asst');
    store.clear();
    expect(store.hasAny).toBe(false);
  });
});
