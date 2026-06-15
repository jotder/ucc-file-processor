/* eslint-disable */
import { GammaNavigationItem } from '@gamma/components/navigation';

export const defaultNavigation: GammaNavigationItem[] = [
    {
        id   : 'dashboard',
        title: 'Dashboard',
        type : 'basic',
        icon : 'heroicons_outline:chart-bar',
        link : '/dashboard'
    },
    {
        id   : 'pipelines',
        title: 'Pipelines',
        type : 'basic',
        icon : 'heroicons_outline:arrows-right-left',
        link : '/pipelines'
    },
    {
        id   : 'jobs',
        title: 'Jobs',
        type : 'basic',
        icon : 'heroicons_outline:clock',
        link : '/jobs'
    },
    {
        id   : 'enrichment',
        title: 'Enrichment',
        type : 'basic',
        icon : 'heroicons_outline:funnel',
        link : '/enrichment'
    },
    {
        id   : 'catalog',
        title: 'Catalog',
        type : 'basic',
        icon : 'heroicons_outline:share',
        link : '/catalog'
    },
    {
        id   : 'diagnoses',
        title: 'Diagnoses',
        type : 'basic',
        icon : 'heroicons_outline:wrench-screwdriver',
        link : '/diagnoses'
    },
    {
        id   : 'events',
        title: 'Events',
        type : 'basic',
        icon : 'heroicons_outline:queue-list',
        link : '/events'
    },
    {
        id   : 'alerts',
        title: 'Alerts',
        type : 'basic',
        icon : 'heroicons_outline:bell-alert',
        link : '/alerts'
    },
    {
        id   : 'issues',
        title: 'Issues',
        type : 'basic',
        icon : 'heroicons_outline:exclamation-triangle',
        link : '/issues'
    },
    {
        id   : 'cases',
        title: 'Cases',
        type : 'basic',
        icon : 'heroicons_outline:briefcase',
        link : '/cases'
    },
    {
        id   : 'sources',
        title: 'Sources',
        type : 'basic',
        icon : 'heroicons_outline:inbox-arrow-down',
        link : '/sources'
    },
    {
        id   : 'connections',
        title: 'Connections',
        type : 'basic',
        icon : 'heroicons_outline:server-stack',
        link : '/connections'
    },
    {
        id   : 'config',
        title: 'Config',
        type : 'basic',
        icon : 'heroicons_outline:adjustments-horizontal',
        link : '/config'
    },
    {
        id   : 'assist',
        title: 'Assistant',
        type : 'basic',
        icon : 'heroicons_outline:sparkles',
        link : '/assist'
    },
    {
        id   : 'model-settings',
        title: 'Model Settings',
        type : 'basic',
        icon : 'heroicons_outline:cpu-chip',
        link : '/settings/models'
    }
];
// The alternate layouts reuse the same Inspecto navigation.
export const compactNavigation: GammaNavigationItem[] = defaultNavigation;
export const futuristicNavigation: GammaNavigationItem[] = defaultNavigation;
export const horizontalNavigation: GammaNavigationItem[] = defaultNavigation;
