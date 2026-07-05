/* eslint-disable */
import { GammaNavigationItem } from '@gamma/components/navigation';

export const defaultNavigation: GammaNavigationItem[] = [
    {
        id      : 'platform-group',
        title   : 'Platform',
        type    : 'collapsable',
        icon    : 'heroicons_outline:squares-2x2',
        children: [
            {
                id      : 'workbench-group',
                title   : 'Workbench',
                type    : 'collapsable',
                icon    : 'heroicons_outline:wrench',
                children: [
                    { id: 'pipelines',   title: 'Pipelines',   type: 'basic', icon: 'heroicons_outline:share',            link: '/pipelines' },
                    { id: 'runs',        title: 'Runs',        type: 'basic', icon: 'heroicons_outline:queue-list',       link: '/runs' },
                    { id: 'jobs',        title: 'Jobs',        type: 'basic', icon: 'heroicons_outline:clock',            link: '/jobs' },
                    { id: 'expectations', title: 'Expectations', type: 'basic', icon: 'heroicons_outline:check-badge',   link: '/expectations' },
                    { id: 'decision-rules', title: 'Decision Rules', type: 'basic', icon: 'heroicons_outline:arrows-right-left', link: '/decision-rules' },
                    { id: 'components',  title: 'Components',  type: 'basic', icon: 'heroicons_outline:puzzle-piece',     link: '/components' },
                    { id: 'enrichment',  title: 'Enrichment',  type: 'basic', icon: 'heroicons_outline:funnel',          link: '/enrichment' },
                    { id: 'connections', title: 'Connections', type: 'basic', icon: 'heroicons_outline:server-stack',     link: '/connections' },
                    { id: 'sources',     title: 'Sources',     type: 'basic', icon: 'heroicons_outline:inbox-arrow-down', link: '/sources' },
                ]
            },
            {
                id      : 'studio-group',
                title   : 'Studio',
                type    : 'collapsable',
                icon    : 'heroicons_outline:presentation-chart-line',
                children: [
                    { id: 'studio-widgets',    title: 'Widget Builder', type: 'basic', icon: 'heroicons_outline:chart-pie',   link: '/studio/widgets' },
                    { id: 'studio-dashboards', title: 'Dashboards',     type: 'basic', icon: 'heroicons_outline:squares-2x2', link: '/studio/dashboards' },
                    { id: 'studio-link-analysis', title: 'Link Analysis', type: 'basic', icon: 'heroicons_outline:share',    link: '/studio/link-analysis' },
                    { id: 'studio-geo-map',    title: 'Geo Map Analysis', type: 'basic', icon: 'heroicons_outline:globe-alt', link: '/studio/geo-map' },
                ]
            },
            {
                id      : 'catalog-group',
                title   : 'Catalog',
                type    : 'collapsable',
                icon    : 'heroicons_outline:rectangle-group',
                children: [
                    { id: 'catalog',          title: 'Data Catalog', type: 'basic', icon: 'heroicons_outline:share',           link: '/catalog' },
                    { id: 'studio-datasets',  title: 'Datasets',     type: 'basic', icon: 'heroicons_outline:table-cells',     link: '/catalog/datasets' },
                ]
            },
        ]
    },
    {
        id      : 'operations-group',
        title   : 'Operations',
        type    : 'collapsable',
        icon    : 'heroicons_outline:bolt',
        children: [
            { id: 'op-overview', title: 'Overview',   type: 'basic', icon: 'heroicons_outline:chart-bar',             link: '/dashboard' },
            { id: 'processing-status', title: 'Processing Status', type: 'basic', icon: 'heroicons_outline:signal',   link: '/processing-status' },
            { id: 'events',      title: 'Events',     type: 'basic', icon: 'heroicons_outline:queue-list',            link: '/events' },
            { id: 'audit',       title: 'Audit log',  type: 'basic', icon: 'heroicons_outline:shield-check',          link: '/audit' },
            { id: 'diagnoses',   title: 'Diagnoses',  type: 'basic', icon: 'heroicons_outline:wrench-screwdriver',    link: '/diagnoses' },
            { id: 'alerts',      title: 'Alerts',     type: 'basic', icon: 'heroicons_outline:bell-alert',            link: '/alerts' },
            { id: 'incidents',   title: 'Incidents',  type: 'basic', icon: 'heroicons_outline:exclamation-triangle',  link: '/incidents' },
            { id: 'cases',       title: 'Cases',      type: 'basic', icon: 'heroicons_outline:briefcase',             link: '/cases' }
        ]
    },
    {
        id   : 'kpi-reports',
        title: 'KPI & Reports',
        type : 'basic',
        icon : 'heroicons_outline:document-chart-bar',
        link : '/kpi-reports'
    },
    {
        id   : 'requirements',
        title: 'Requirements',
        type : 'basic',
        icon : 'heroicons_outline:inbox-stack',
        link : '/requirements'
    },
    {
        id   : 'reconciliation',
        title: 'Reconciliation',
        type : 'basic',
        icon : 'heroicons_outline:scale',
        link : '/reconciliation'
    },
    {
        id      : 'settings-group',
        title   : 'Settings',
        type    : 'collapsable',
        icon    : 'heroicons_outline:cog-8-tooth',
        children: [
            { id: 'config',         title: 'Config',         type: 'basic', icon: 'heroicons_outline:adjustments-horizontal', link: '/config' },
            { id: 'notification-center', title: 'Notifications', type: 'basic', icon: 'heroicons_outline:bell',               link: '/notification-center' },
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
