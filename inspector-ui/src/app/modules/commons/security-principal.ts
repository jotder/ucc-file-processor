import { Injectable, inject, signal, computed } from '@angular/core';
import { jwtDecode } from 'jwt-decode';
import { AppLocalStorage } from '../auth/app-local-storage.service';

@Injectable({
    providedIn: 'root',
})
export class SecurityPrincipal {

    // -------------------------------------------------------------------------
    // Injected dependencies
    // -------------------------------------------------------------------------
    private readonly localStorage = inject(AppLocalStorage);

    // -------------------------------------------------------------------------
    // Private state — signals replace BehaviorSubjects
    // -------------------------------------------------------------------------
    private readonly _principalLoaded = signal<any[]>([]);
    private readonly _profileLoaded   = signal<any[]>([]);
    private readonly _appMenuLoaded   = signal<any[]>([]);

    /** Replaces onPrincipalLoad BehaviorSubject */
    readonly onPrincipalLoad = this._principalLoaded.asReadonly();

    /** Replaces onProfileLoad BehaviorSubject */
    readonly onProfileLoad = this._profileLoaded.asReadonly();

    /** Replaces onAppMenuLoad BehaviorSubject */
    readonly onAppMenuLoad = this._appMenuLoaded.asReadonly();

    // -------------------------------------------------------------------------
    // Private fields
    // -------------------------------------------------------------------------
    private userName!: string;
    private fullName!: string;
    private email!: string;
    private phone!: string;
    private clientID!: string;
    private refreshToken!: string;
    private accessToken!: string;
    private principalName!: string;
    private principal: any;
    private expiresIn!: string;
    private authorities!: string[];
    private groups!: string[];
    private jwtId: any;
    private issueAt: any;
    private expirationTime: any;
    private payload: any;
    private userProfile: any;
    private appmenu: any[] = [];

    // Public fields
    roleDetails!: string[];
    groupDetails!: string[];
    associatedTenants!: string[];

    // -------------------------------------------------------------------------
    // Computed signals (derived state — replaces downstream subscriptions)
    // -------------------------------------------------------------------------
    readonly isAuthenticated = computed(() => {
        const loaded = this._principalLoaded();
        return loaded.length > 0 && this.isLoggedIn();
    });

    readonly currentUserProfile = computed(() => {
        const loaded = this._profileLoaded();
        return loaded.length > 0 ? loaded[0] : null;
    });

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------
    constructor() {
        this.accessToken  = this.localStorage.get('access_token');
        this.refreshToken = this.localStorage.get('refresh_token');
        this.jwtId        = this.localStorage.get('jti');
        this.issueAt      = this.localStorage.get('iat');
        this.expiresIn    = this.localStorage.get('expires_in');

        if (this.accessToken) {
            this.decodeJwtToken(this.accessToken);
        }
    }

    // -------------------------------------------------------------------------
    // Principal loading
    // -------------------------------------------------------------------------

    loadPrincipalData(data: any): void {
        if (data && !this.isLoggedIn()) {
            this.payload      = data;
            this.accessToken  = data['access_token'];
            this.refreshToken = data['refresh_token'];
            this.jwtId        = data['jti'];
            this.issueAt      = data['iat'];
            this.expiresIn    = data['expires_in'];

            this.localStorage.save('access_token', this.accessToken);
            this.localStorage.save('refresh_token', this.refreshToken);
            this.localStorage.save('jti', this.jwtId);
            this.localStorage.save('iat', this.issueAt);
            this.localStorage.save('expires_in', this.expiresIn);

            this.decodeJwtToken(this.accessToken);

            // Signal update replaces BehaviorSubject.next()
            this._principalLoaded.set([data]);
        }
    }

    decodeJwtToken(token: string): void {
        const decoded: any = jwtDecode(token);  // named export in jwt-decode v4+

        this.principal       = decoded;
        this.principalName   = decoded['sub'] || decoded['user_name'];
        this.authorities     = decoded['scope'];
        this.clientID        = decoded['client_id'];
        this.expirationTime  = decoded['exp'];

        if (!this.localStorage.get('decoded'))     this.localStorage.save('decoded', decoded);
        if (!this.localStorage.get('user_name'))   this.localStorage.save('user_name', this.principalName);
        if (!this.localStorage.get('authorities')) this.localStorage.save('authorities', this.authorities);
        if (!this.localStorage.get('client_id'))   this.localStorage.save('client_id', this.clientID);
        if (!this.localStorage.get('exp'))         this.localStorage.save('exp', this.expirationTime);
    }

    clear(): void {
        this.principal      = null;
        this.principalName  = null!;
        this.authorities    = null!;
        this.clientID       = null!;
        this.accessToken    = null!;
        this.expirationTime = null;

        ['user_name', 'authorities', 'client_id', 'access_token', 'refresh_token', 'exp']
            .forEach(key => this.localStorage.delete(key));

        // Reset signals
        this._principalLoaded.set([]);
        this._profileLoaded.set([]);
    }

    // -------------------------------------------------------------------------
    // App menu
    // -------------------------------------------------------------------------

    createAppMenu(data: any[]): void {
        this.appmenu = data;
        if (data?.length > 0) {
            this._appMenuLoaded.set([data]);
        }
    }

    getAllMenuItems(): any[] {
        return this.appmenu;
    }

    getAppMenu(): any[] | undefined {
        if (this.appmenu?.length > 0) {
            return this.buildMenuTree(null, this.appmenu);
        }
        return undefined;
    }

    // -------------------------------------------------------------------------
    // Token accessors
    // -------------------------------------------------------------------------

    getClientId(): string {
        return this.clientID ?? this.localStorage.get('client_id');
    }

    getAccessToken(): string {
        return this.accessToken ?? this.localStorage.get('access_token');
    }

    getRefreshToken(): string {
        return this.refreshToken ?? this.localStorage.get('refresh_token');
    }

    getPrincipalName(): string {
        return this.principalName ?? this.localStorage.get('user_name');
    }

    getPrincipal(): any {
        return this.principal;
    }

    getExpiresIn(): string {
        return this.expiresIn ?? this.localStorage.get('expires_in');
    }

    getJwtId(): any {
        return this.jwtId ?? this.localStorage.get('jti');
    }

    getIssueAt(): any {
        return this.issueAt ?? this.localStorage.get('iat');
    }

    getExpirationTime(): any {
        return this.expirationTime ?? this.localStorage.get('exp');
    }

    getAuthorities(): string[] {
        if (!this.authorities) {
            this.authorities = this.localStorage.get('authorities').split('|');
        }
        return this.authorities;
    }

    getGroups(): string[] {
        if (!this.groups) {
            this.groups = this.localStorage.get('groups').split('|');
        }
        return this.groups;
    }

    // -------------------------------------------------------------------------
    // Token mutators
    // -------------------------------------------------------------------------

    setAccessToken(accessToken: string): void {
        this.accessToken = accessToken;
        this.localStorage.save('access_token', accessToken);
    }

    setRefreshToken(refreshToken: string): void {
        this.refreshToken = refreshToken;
        this.localStorage.save('refresh_token', refreshToken);
    }

    setExpiresIn(expiresIn: string): void {
        this.expiresIn = expiresIn;
        this.localStorage.save('expires_in', expiresIn);
    }

    // -------------------------------------------------------------------------
    // Auth checks
    // -------------------------------------------------------------------------

    isLoggedIn(): boolean {
        if (this.principal) {
            const currentTimeInSec = Math.round(Date.now() / 1000);
            return this.expirationTime > currentTimeInSec;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Profile loading
    // -------------------------------------------------------------------------

    loadUserProfile(userData: any): void {
        if (!userData) return;

        this.userProfile       = userData;
        this.userName          = userData['userName'];
        this.fullName          = userData['fullName'];
        this.email             = userData['email'];
        this.phone             = userData['phone'];

        if (userData['memberOfGroups'])  this.groups            = userData['memberOfGroups'];
        if (userData['roleDetails'])     this.roleDetails       = userData['roleDetails'];
        if (userData['groupDetails'])    this.groupDetails      = userData['groupDetails'];
        if (userData['associatedTenants']) this.associatedTenants = userData['associatedTenants'];

        // Signal update replaces onProfileLoad.next()
        this._profileLoaded.set([userData]);
    }

    // -------------------------------------------------------------------------
    // Role / group helpers
    // -------------------------------------------------------------------------

    getRoleNames(): string[] {
        const roleNames: string[] = [];

        if (this.roleDetails) {
            for (const roleId in this.roleDetails) {
                const roleKey = this.roleDetails[roleId]['roleInfo']['roleKey'];
                if (!roleNames.includes(roleKey)) roleNames.push(roleKey);
            }
        }

        if (this.groupDetails) {
            for (const groupId in this.groupDetails) {
                const group = this.groupDetails[groupId];
                if (group['roleDetails']) {
                    Object.keys(group['roleDetails']).forEach(roleId => {
                        const roleKey = group['roleDetails'][roleId]['roleInfo']['roleKey'];
                        if (!roleNames.includes(roleKey)) roleNames.push(roleKey);
                    });
                }
            }
        }

        return roleNames;
    }

    getRoleDetails(roleId: string): any {
        return this.roleDetails?.[roleId] ?? null;
    }

    getGroupDetails(groupId: string): any {
        return this.groupDetails?.[groupId] ?? null;
    }

    getTargetTenantIds(): any[] | null {
        return this.associatedTenants ?? null;
    }

    getGuiRoleDetailsIfAny(): any[] {
        const guiRoles: any[] = [];

        if (this.roleDetails) {
            Object.keys(this.roleDetails).forEach(roleId => {
                const role = this.roleDetails[roleId];
                if (role['roleInfo']['roleType'] === 'gui') guiRoles.push(role);
            });
        }

        if (this.groupDetails) {
            for (const groupId in this.groupDetails) {
                const group = this.groupDetails[groupId];
                if (group['roleDetails']) {
                    Object.keys(group['roleDetails']).forEach(roleId => {
                        const role = group['roleDetails'][roleId];
                        if (role['roleInfo']['roleType'] === 'gui') guiRoles.push(role);
                    });
                }
            }
        }

        return guiRoles;
    }

    // -------------------------------------------------------------------------
    // Menu tree builder
    // -------------------------------------------------------------------------

    buildMenuTree(userName: string | null, menuData: any[]): any[] {
        const nodeMap = new Map<string, any>();
        const tree: any[] = [];

        menuData.forEach(menu => {
            if (!this.getPermissionEntity(menu.permissions)) return;

            const tid = menu.childMenuTid || menu.parentMenuTid;
            nodeMap.set(tid, {
                id:            menu.menuId,
                title:         menu.title,
                icon:          menu.icon,
                type:          menu.type,
                link:          menu.link ? menu.link.replace(/^\/+/, '') : undefined,
                queryParams:   menu.queryParams,
                parentMenuTid: menu.parentMenuTid,
                childMenuTid:  menu.childMenuTid,
                children:      [],
            });
        });

        nodeMap.forEach((node) => {
            if (!node.childMenuTid) {
                tree.push(node);
                return;
            }

            const parentTid = node.childMenuTid.split('.').slice(0, -1).join('.');
            const parent    = nodeMap.get(parentTid) ?? nodeMap.get(node.parentMenuTid);

            if (parent) parent.children.push(node);
        });

        return tree;
    }

    // -------------------------------------------------------------------------
    // Permission check
    // -------------------------------------------------------------------------

    getPermissionEntity(permission: any): boolean {
        if (!permission || !Array.isArray(permission)) return false;

        const username = this.getPrincipalName();

        for (const item of permission) {
            const { entityName, entityValues } = item;

            if (
                entityName === 'user' &&
                Array.isArray(entityValues) &&
                (entityValues.includes('{USER}') || entityValues.includes(username))
            ) {
                return true;
            }

            if (entityName === 'role' && Array.isArray(entityValues)) {
                if (entityValues.includes('{ROLE}')) return true;
                return this.roleDetails
                    ? entityValues.every(t => this.roleDetails.includes(t))
                    : false;
            }

            if (entityName === 'group' && Array.isArray(entityValues)) {
                if (entityValues.includes('{GROUP}')) return true;
                if (!this.groupDetails) return false;

                const groupNames = Object.values(this.groupDetails)
                    .map((group: any) => group.groupName);
                return entityValues.every(value => groupNames.includes(value));
            }

            if (entityName === 'tenant_id' && Array.isArray(entityValues)) {
                const associatedTenants = this.getTargetTenantIds();
                return associatedTenants
                    ? entityValues.some(t => associatedTenants.includes(t))
                    : true;
            }
        }

        return false;
    }
}