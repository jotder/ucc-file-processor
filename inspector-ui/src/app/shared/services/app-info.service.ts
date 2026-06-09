import { Injectable } from '@angular/core';

/**
 * Brand + app metadata. {@link #title} is the single source for the header, footer and document
 * title. The operator UI is branded "Inspector" (the engine lives in com.gamma.inspector).
 */
@Injectable()
export class AppInfoService {
  constructor() {}

  public get title() {
    return 'Inspector';
  }

  /** Short tagline shown on the Connect screen / about. */
  public get tagline() {
    return 'UCC File Processing — operator console';
  }

  public get currentYear() {
    return new Date().getFullYear();
  }
}
