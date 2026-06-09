type NavigationItem = { path?: string, text: string, icon?: string, items?: NavigationItem[] };
export const navigation: NavigationItem[] = [
  { text: 'Dashboard', path: '/dashboard', icon: 'home' },
  { text: 'Pipelines', path: '/pipelines', icon: 'detailslayout' },
  { text: 'Jobs & Schedules', path: '/jobs', icon: 'clock' },
  { text: 'Enrichment', path: '/enrichment', icon: 'datatrending' },
  { text: 'Catalog', path: '/catalog', icon: 'hierarchy' },
  { text: 'Configuration', path: '/config', icon: 'preferences' },
  { text: 'Diagnoses', path: '/diagnoses', icon: 'warning' },
  { text: 'Assistant', path: '/assist', icon: 'comment' },
];
