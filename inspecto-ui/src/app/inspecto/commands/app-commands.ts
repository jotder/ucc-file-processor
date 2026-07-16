import { registerCommand } from './command-registry';

/**
 * The built-in feature commands (side-effect registration, imported by the classic layout). Each is
 * pure navigation: the target pane owns the `?create=1` handshake (opens its create dialog, strips
 * the param), so no feature code is imported here or in the shell.
 */
registerCommand({ title: 'New incident', icon: 'heroicons_outline:plus-circle', group: 'Create', link: '/incidents', queryParams: { create: 1 } });
registerCommand({ title: 'New case', icon: 'heroicons_outline:plus-circle', group: 'Create', link: '/cases', queryParams: { create: 1 } });
registerCommand({ title: 'New job', icon: 'heroicons_outline:plus-circle', group: 'Create', link: '/jobs', queryParams: { create: 1 } });
