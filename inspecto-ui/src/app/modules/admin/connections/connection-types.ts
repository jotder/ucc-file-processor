/**
 * Connection-type catalog — the schema behind the connection configuration dialog (mirrors the parser
 * `parser-types.ts` pattern). Each type maps to a backend `connector` and declares the typed attributes the
 * form should show. An attribute's {@link ConnAttr.target} says where it lands on the `ConnectionProfile`:
 * a top-level field (host/port/database/basePath/username/password) or inside the free-form `options` map.
 *
 * The SSH-tunnel (bastion) and proxy sections are shared across every type and are handled directly by the
 * dialog (they map to `profile.tunnel` / `profile.proxy`), so they are NOT part of these per-type attrs.
 *
 * NOTE: only the field NAMES are firm (they match the existing `ConnectionProfile` shape + `*_connection.toon`
 * conventions). The exact per-type option lists are a BEST-GUESS first cut — revisit as each connector's real
 * settings firm up (same caveat as the non-DSV parser types).
 */

/** Control type for a connection attribute field. */
export type ConnControl = 'text' | 'number' | 'password' | 'checkbox' | 'select';

/** Where an attribute lands on the ConnectionProfile. `option` ⇒ the free-form `options` map. */
export type ConnTarget = 'host' | 'port' | 'database' | 'basePath' | 'username' | 'password' | 'option';

export interface ConnAttr {
    /** Form-control key. For non-`option` targets this equals the ConnectionProfile field name. */
    key: string;
    label: string;
    control: ConnControl;
    target: ConnTarget;
    default?: string | number | boolean;
    options?: string[];
    hint?: string;
    required?: boolean;
}

export interface ConnTypeDef {
    /** Dropdown value / UI id. */
    type: string;
    /** The `ConnectionProfile.connector` sent to the backend. */
    connector: string;
    label: string;
    description: string;
    attrs: ConnAttr[];
}

const SECRET_HINT = 'a ${ENV:VAR} reference, not a raw secret';

/** The five connection types offered in the dropdown (order as specified). */
export const CONNECTION_TYPES: ConnTypeDef[] = [
    {
        type: 'database',
        connector: 'db',
        label: 'Database',
        description: 'Connect to a database',
        attrs: [
            { key: 'db_type', label: 'Database type', control: 'select', target: 'option', default: 'postgres', options: ['postgres', 'mysql', 'oracle', 'sqlserver', 'duckdb'] },
            { key: 'host', label: 'Host', control: 'text', target: 'host', required: true },
            { key: 'port', label: 'Port', control: 'number', target: 'port', default: 5432 },
            { key: 'database', label: 'Database', control: 'text', target: 'database', required: true },
            { key: 'username', label: 'Username', control: 'text', target: 'username' },
            { key: 'password', label: 'Password', control: 'password', target: 'password', hint: SECRET_HINT },
            { key: 'schema', label: 'Default schema', control: 'text', target: 'option' },
            { key: 'sslmode', label: 'SSL mode', control: 'select', target: 'option', default: 'require', options: ['disable', 'require', 'verify-ca', 'verify-full'] },
        ],
    },
    {
        type: 'ftp',
        connector: 'ftp',
        label: 'FTP',
        description: 'Connect to an FTP server',
        attrs: [
            { key: 'host', label: 'Host', control: 'text', target: 'host', required: true },
            { key: 'port', label: 'Port', control: 'number', target: 'port', default: 21 },
            { key: 'username', label: 'Username', control: 'text', target: 'username' },
            { key: 'password', label: 'Password', control: 'password', target: 'password', hint: SECRET_HINT },
            { key: 'basePath', label: 'Base path', control: 'text', target: 'basePath' },
            { key: 'passive_mode', label: 'Passive mode', control: 'checkbox', target: 'option', default: true },
            { key: 'encoding', label: 'Encoding', control: 'text', target: 'option', default: 'UTF-8' },
        ],
    },
    {
        type: 'ftps',
        connector: 'ftps',
        label: 'FTPS',
        description: 'Connect to an FTPS (TLS) server',
        attrs: [
            { key: 'host', label: 'Host', control: 'text', target: 'host', required: true },
            { key: 'port', label: 'Port', control: 'number', target: 'port', default: 990 },
            { key: 'username', label: 'Username', control: 'text', target: 'username' },
            { key: 'password', label: 'Password', control: 'password', target: 'password', hint: SECRET_HINT },
            { key: 'basePath', label: 'Base path', control: 'text', target: 'basePath' },
            { key: 'implicit_tls', label: 'Implicit TLS', control: 'checkbox', target: 'option', default: true },
            { key: 'passive_mode', label: 'Passive mode', control: 'checkbox', target: 'option', default: true },
        ],
    },
    {
        type: 'local',
        connector: 'local',
        label: 'Local',
        description: 'Files on the local host (directory with permission)',
        attrs: [
            { key: 'basePath', label: 'Directory', control: 'text', target: 'basePath', required: true },
            { key: 'file_pattern', label: 'File pattern', control: 'text', target: 'option', hint: 'glob, e.g. *.csv.gz' },
            { key: 'recursive', label: 'Recurse subdirectories', control: 'checkbox', target: 'option', default: false },
        ],
    },
    {
        type: 'sftp',
        connector: 'sftp',
        label: 'SFTP',
        description: 'Connect to an SFTP server',
        attrs: [
            { key: 'host', label: 'Host', control: 'text', target: 'host', required: true },
            { key: 'port', label: 'Port', control: 'number', target: 'port', default: 22 },
            { key: 'username', label: 'Username', control: 'text', target: 'username', required: true },
            { key: 'auth_method', label: 'Auth method', control: 'select', target: 'option', default: 'password', options: ['password', 'key'] },
            { key: 'password', label: 'Password / passphrase', control: 'password', target: 'password', hint: SECRET_HINT },
            { key: 'private_key_path', label: 'Private key path', control: 'text', target: 'option', hint: 'path to the private key (key auth)' },
            { key: 'basePath', label: 'Remote directory', control: 'text', target: 'basePath' },
            { key: 'strict_host_key', label: 'Strict host-key checking', control: 'checkbox', target: 'option', default: true },
        ],
    },
];

/** The type definition for {@code type}, falling back to the first type for an unknown value. */
export function connTypeDef(type: string): ConnTypeDef {
    return CONNECTION_TYPES.find((t) => t.type === type) ?? CONNECTION_TYPES[0];
}

/** The dialog type id for a saved profile's {@code connector} (defaults to the first type if unmapped). */
export function typeForConnector(connector: string): string {
    return (CONNECTION_TYPES.find((t) => t.connector === connector) ?? CONNECTION_TYPES[0]).type;
}

/** The typed attributes for {@code type}. */
export function attrsFor(type: string): ConnAttr[] {
    return connTypeDef(type).attrs;
}
