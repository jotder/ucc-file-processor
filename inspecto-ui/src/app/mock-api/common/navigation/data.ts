/* eslint-disable */
import { GammaNavigationItem } from '@gamma/components/navigation';
import { cloneDeep } from 'lodash-es';

export const defaultNavigation: GammaNavigationItem[] = [
    // Separator above the platform groups. User-defined custom / business menus (Menu Builder) are
    // prepended above this divider in the navigation mock (see navigation/api.ts), so this line marks
    // the boundary between custom menus (top) and the built-in platform groups (below).
    {
        id  : 'custom-menus-divider',
        type: 'divider'
    },
    {
        id      : 'business-group',
        title   : 'Business',
        type    : 'collapsable',
        icon    : 'heroicons_outline:building-office',
        children: [
            { id: 'kpi-reports',    title: 'KPI & Reports',  type: 'basic', icon: 'heroicons_outline:document-chart-bar', link: '/kpi-reports' },
            { id: 'requirements',   title: 'Requirements',   type: 'basic', icon: 'heroicons_outline:inbox-stack',        link: '/requirements' },
            { id: 'reconciliation', title: 'Reconciliation', type: 'basic', icon: 'heroicons_outline:scale',              link: '/reconciliation' }
        ]
    },
    {
        id      : 'operations-group',
        title   : 'Operations',
        type    : 'collapsable',
        icon    : 'heroicons_outline:bolt',
        children: [
            { id: 'op-overview', title: 'Overview',   type: 'basic', icon: 'heroicons_outline:chart-bar',             link: '/overview' },
            { id: 'processing-status', title: 'Processing Status', type: 'basic', icon: 'heroicons_outline:signal',   link: '/processing-status' },
            { id: 'events',      title: 'Events',     type: 'basic', icon: 'heroicons_outline:queue-list',            link: '/events' },
            { id: 'audit',       title: 'Audit log',  type: 'basic', icon: 'heroicons_outline:shield-check',          link: '/audit' },
            { id: 'diagnoses',   title: 'Diagnoses',  type: 'basic', icon: 'heroicons_outline:wrench-screwdriver',    link: '/diagnoses' },
            { id: 'alerts',      title: 'Alerts',     type: 'basic', icon: 'heroicons_outline:bell-alert',            link: '/alerts' },
            { id: 'incidents',   title: 'Incidents',  type: 'basic', icon: 'heroicons_outline:exclamation-triangle',  link: '/incidents' },
            { id: 'cases',       title: 'Case Manager', type: 'basic', icon: 'heroicons_outline:briefcase',           link: '/cases' }
        ]
    },
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
                    { id: 'studio-queries',    title: 'Query Library',  type: 'basic', icon: 'heroicons_outline:command-line', link: '/studio/queries' },
                    { id: 'studio-viz-library', title: 'Viz Library',    type: 'basic', icon: 'heroicons_outline:rectangle-stack', link: '/studio/widgets' },
                    { id: 'studio-dashboards', title: 'Dashboard Builder', type: 'basic', icon: 'heroicons_outline:squares-2x2', link: '/studio/dashboards' },
                    { id: 'studio-templates',  title: 'Template Gallery', type: 'basic', icon: 'heroicons_outline:sparkles', link: '/studio/templates' },
                    { id: 'studio-link-analysis', title: 'Link Analysis', type: 'basic', icon: 'heroicons_outline:share',    link: '/studio/link-analysis' },
                    { id: 'menus',             title: 'Menus',          type: 'basic', icon: 'heroicons_outline:bars-3',    link: '/settings/menus' },
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
        // System Maintenance (plan §5): the platform maintaining ITSELF — sibling of Operations (which is
        // the platform working for the business). Items appear with the phase that produces their data;
        // Phase 3 ships the Overview (health, job fleet, failed runs, storage). Platform nav, not a
        // Menu Builder artifact (the builder edits custom menus only).
        id      : 'system-maintenance-group',
        title   : 'System Maintenance',
        type    : 'collapsable',
        icon    : 'heroicons_outline:wrench-screwdriver',
        children: [
            { id: 'maintenance-overview', title: 'Overview', type: 'basic', icon: 'heroicons_outline:heart', link: '/maintenance' }
        ]
    },
    {
        id   : 'settings',
        title: 'Settings',
        type : 'basic',
        icon : 'heroicons_outline:cog-8-tooth',
        link : '/settings'
    },
    {
        id   : 'assist',
        title: 'Assistant',
        type : 'basic',
        icon : 'heroicons_outline:sparkles',
        link : '/assist'
    }
];
// The alternate layouts reuse the same Inspecto navigation, but each gets its OWN array so the mock's
// in-place child-fill (api.ts) can't mutate a shared reference and produce duplicate items (NG0955).
export const compactNavigation: GammaNavigationItem[] = cloneDeep(defaultNavigation);
export const futuristicNavigation: GammaNavigationItem[] = cloneDeep(defaultNavigation);
export const horizontalNavigation: GammaNavigationItem[] = cloneDeep(defaultNavigation);
