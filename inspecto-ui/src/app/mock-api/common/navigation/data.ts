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
        id      : 'pipelines-group',
        title   : 'Pipelines',
        type    : 'collapsable',
        icon    : 'heroicons_outline:arrows-right-left',
        children: [
            { id: 'flows',      title: 'Pipelines',  type: 'basic', icon: 'heroicons_outline:share',                  link: '/flows' },
            { id: 'pipelines',  title: 'Runs',       type: 'basic', icon: 'heroicons_outline:queue-list',             link: '/pipelines' },
            { id: 'components', title: 'Components', type: 'basic', icon: 'heroicons_outline:puzzle-piece',          link: '/components' },
            { id: 'enrichment', title: 'Enrichment', type: 'basic', icon: 'heroicons_outline:funnel',                 link: '/enrichment' },
            { id: 'catalog',    title: 'Catalog',    type: 'basic', icon: 'heroicons_outline:share',                  link: '/catalog' }
        ]
    },
    {
        id      : 'acquisition-group',
        title   : 'Acquisition',
        type    : 'collapsable',
        icon    : 'heroicons_outline:inbox-arrow-down',
        children: [
            { id: 'sources',     title: 'Sources',     type: 'basic', icon: 'heroicons_outline:inbox-arrow-down', link: '/sources' },
            { id: 'connections', title: 'Connections', type: 'basic', icon: 'heroicons_outline:server-stack',     link: '/connections' }
        ]
    },
    {
        id      : 'operations-group',
        title   : 'Operations',
        type    : 'collapsable',
        icon    : 'heroicons_outline:bolt',
        children: [
            { id: 'scheduler', title: 'Scheduler', type: 'basic', icon: 'heroicons_outline:clock',                 link: '/jobs' },
            { id: 'events',    title: 'Events',    type: 'basic', icon: 'heroicons_outline:queue-list',            link: '/events' },
            { id: 'diagnoses', title: 'Diagnoses', type: 'basic', icon: 'heroicons_outline:wrench-screwdriver',    link: '/diagnoses' },
            { id: 'alerts',    title: 'Alerts',    type: 'basic', icon: 'heroicons_outline:bell-alert',            link: '/alerts' },
            { id: 'issues',    title: 'Issues',    type: 'basic', icon: 'heroicons_outline:exclamation-triangle',  link: '/issues' },
            { id: 'cases',     title: 'Cases',     type: 'basic', icon: 'heroicons_outline:briefcase',             link: '/cases' }
        ]
    },
    {
        id      : 'studio-group',
        title   : 'Studio',
        type    : 'collapsable',
        icon    : 'heroicons_outline:presentation-chart-line',
        children: [
            { id: 'studio-datasets',   title: 'Datasets',   type: 'basic', icon: 'heroicons_outline:table-cells',     link: '/studio/datasets' },
            { id: 'studio-charts',     title: 'Charts',     type: 'basic', icon: 'heroicons_outline:chart-pie',       link: '/studio/charts' },
            { id: 'studio-dashboards', title: 'Dashboards', type: 'basic', icon: 'heroicons_outline:squares-2x2',     link: '/studio/dashboards' },
            { id: 'studio-registry',   title: 'Registry',   type: 'basic', icon: 'heroicons_outline:rectangle-group', link: '/registry' }
        ]
    },
    {
        id      : 'settings-group',
        title   : 'Settings',
        type    : 'collapsable',
        icon    : 'heroicons_outline:cog-8-tooth',
        children: [
            { id: 'config',         title: 'Config',         type: 'basic', icon: 'heroicons_outline:adjustments-horizontal', link: '/config' },
            { id: 'spaces',         title: 'Spaces',         type: 'basic', icon: 'heroicons_outline:square-3-stack-3d',      link: '/spaces' },
            { id: 'model-settings', title: 'Model Settings', type: 'basic', icon: 'heroicons_outline:cpu-chip',               link: '/settings/models' },
            { id: 'icon-settings',  title: 'Processor Icons', type: 'basic', icon: 'heroicons_outline:paint-brush',           link: '/settings/icons' },
            { id: 'design-system',  title: 'Design System',  type: 'basic', icon: 'heroicons_outline:swatch',                 link: '/design' }
        ]
    },
    {
        id   : 'assist',
        title: 'Assistant',
        type : 'basic',
        icon : 'heroicons_outline:sparkles',
        link : '/assist'
    }
];
// The alternate layouts reuse the same Inspecto navigation.
export const compactNavigation: GammaNavigationItem[] = defaultNavigation;
export const futuristicNavigation: GammaNavigationItem[] = defaultNavigation;
export const horizontalNavigation: GammaNavigationItem[] = defaultNavigation;
