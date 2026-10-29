export interface Driver {
  id: string
  providerId: string
  label: string
  className: string
  sampleUrl: string | null
  defaultPort: number | null
  defaultDatabase: string | null
  defaultUser: string | null
  dialect: string | null
  categories: string[]
  /** popularity rank; lower is more popular, null = not promoted */
  promoted: number | null
  description: string | null
  libraries?: DriverLibrary[]
}

export interface SshConfig {
  enabled: boolean
  host: string | null
  port: number | null
  user: string | null
  auth: 'PASSWORD' | 'KEY'
  /** omitted/undefined on update keeps the stored secret */
  password?: string | null
  privateKeyPath: string | null
  passphrase?: string | null
  acceptNewHostKeys: boolean
}

/** SSH settings as returned by the server: secrets replaced by flags. */
export interface SshView extends Omit<SshConfig, 'password' | 'passphrase'> {
  hasPassword: boolean
  hasPassphrase: boolean
}

export interface SslConfig {
  enabled: boolean
  mode: 'REQUIRE' | 'VERIFY_CA' | 'VERIFY_FULL'
  caCertPath: string | null
  clientCertPath: string | null
  clientKeyPath: string | null
}

export interface Connection {
  id: string
  name: string
  driverId: string
  host: string | null
  port: number | null
  database: string | null
  user: string | null
  url: string | null
  properties: Record<string, string>
  autoCommit: boolean
  hasPassword: boolean
  connected: boolean
  ssh: SshView | null
  ssl: SslConfig | null
  connectionType: ConnectionType
  readOnly: boolean
  /** PostgreSQL: list every database of the server, not just the one connected to */
  showAllDatabases: boolean
  /** navigator folder, "Clients/ACME"; null = top level */
  folder: string | null
}


export type ConnectionType = 'DEV' | 'TEST' | 'PROD'

/** Create/update payload. `password` undefined keeps the stored one on update. */
export interface ConnectionInput {
  name: string
  driverId: string
  host?: string | null
  port?: number | null
  database?: string | null
  user?: string | null
  password?: string | null
  url?: string | null
  properties?: Record<string, string>
  autoCommit?: boolean
  ssh?: SshConfig | null
  ssl?: SslConfig | null
  connectionType?: ConnectionType
  readOnly?: boolean
  showAllDatabases?: boolean
  folder?: string | null
}

export interface ConnectionTestResult {
  productName: string
  productVersion: string
  driverName: string
  driverVersion: string
}

export interface Container {
  catalog: string | null
  schema: string | null
  label: string
}

export interface TableInfo {
  name: string
  type: string
  remarks: string | null
}

export interface ColumnInfo {
  name: string
  typeName: string
  size: number
  decimalDigits: number | null
  nullable: boolean
  defaultValue: string | null
  position: number
  primaryKey: boolean
  autoIncrement: boolean
  remarks: string | null
}

export interface ForeignKeyInfo {
  name: string | null
  column: string
  refCatalog: string | null
  refSchema: string | null
  refTable: string
  refColumn: string
}

export interface IndexInfo {
  name: string
  column: string | null
  unique: boolean
  position: number
}

export interface TableDetails {
  name: string
  columns: ColumnInfo[]
  /** keys of this table pointing elsewhere: `column` is local, `ref*` the target */
  foreignKeys: ForeignKeyInfo[]
  /** keys of other tables pointing here: `column` is local, `ref*` the referencing side */
  referencedBy: ForeignKeyInfo[]
  indexes: IndexInfo[]
  /** columns that address a row for editing: primary key, else a unique index, else every comparable column */
  rowKey: string[]
  rowKeyKind: 'PRIMARY_KEY' | 'UNIQUE_INDEX' | 'ALL_COLUMNS' | null
}

/** container label -> table -> columns, for SQL autocompletion */
export interface Completion {
  defaultSchema: string | null
  schemas: Record<string, Record<string, string[]>>
  truncated: boolean
}

export type ColumnKind = 'number' | 'text' | 'temporal' | 'boolean' | 'binary' | 'other'

export interface ResultColumn {
  name: string
  typeName: string
  kind: ColumnKind
}

export type CellValue = string | number | boolean | null

export interface QueryResult {
  columns: ResultColumn[]
  rows: CellValue[][]
  truncated: boolean
  updateCount: number | null
  elapsedMs: number
  /** server output: notices, warnings, PRINT, DBMS_OUTPUT */
  messages: string[]
  /** set when the rows come from one table with its primary key: the grid can edit them */
  source?: EditableSource | null
  /** why a result set cannot be edited */
  readOnlyReason?: string | null
}

export interface EditableSource {
  catalog: string | null
  schema: string | null
  table: string
  /** table column shown by each result column; null = read-only (expression, foreign alias) */
  columns: (string | null)[]
  primaryKey: string[]
  keyKind: 'PRIMARY_KEY' | 'UNIQUE_INDEX' | 'ALL_COLUMNS'
}

export interface StatementResult {
  sql: string
  result: QueryResult | null
  error: string | null
}

export interface DataPage {
  columns: ResultColumn[]
  rows: CellValue[][]
  offset: number
  hasMore: boolean
  elapsedMs: number
}

export interface RowChange {
  op: 'INSERT' | 'UPDATE' | 'DELETE'
  key?: Record<string, CellValue>
  values?: Record<string, CellValue>
}

export interface EditResult {
  statements: number
  rowsAffected: number
  /** false: the save joined an open manual transaction and still needs Commit or Rollback */
  committed: boolean
}

export interface DbInfo {
  product: string
  version: string
  dialect: 'postgresql' | 'mysql' | 'mariadb' | 'sqlite' | 'h2' | 'sqlserver' | 'oracle' | 'generic'
  identifierQuote: string
  supportsCatalogs: boolean
  supportsSchemas: boolean
}

export interface SearchHit {
  catalog: string | null
  schema: string | null
  container: string
  name: string
  type: string
}

export interface CellContent {
  text: string | null
  base64: string | null
  size: number
  mime: string | null
  truncated: boolean
}

export interface DbObject {
  name: string
  detail: string | null
}

export interface SchemaObjects {
  procedures: DbObject[]
  functions: DbObject[]
  sequences: DbObject[]
  triggers: DbObject[]
}

export type ObjectKind = 'PROCEDURE' | 'FUNCTION' | 'SEQUENCE' | 'TRIGGER'

export interface Privilege {
  grantor: string | null
  grantee: string
  privilege: string
  grantable: string | null
}

export interface Metric {
  name: string
  value: number
  kind: 'GAUGE' | 'COUNTER'
  unit: string
}

export interface JobView {
  id: string
  label: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  startedAt: string
  endedAt: string | null
  exitCode: number | null
  log: string[]
}

export type IsolationLevel = 'READ_UNCOMMITTED' | 'READ_COMMITTED' | 'REPEATABLE_READ' | 'SERIALIZABLE' | 'NONE'

export interface TransactionState {
  autoCommit: boolean
  isolation: IsolationLevel
}

export interface Scope {
  catalog?: string | null
  schema?: string | null
}

/** A saved SQL script: a `.sql` file in the server's scripts folder. */
export interface ScriptInfo {
  name: string
  connectionId: string | null
  size: number
  modified: number
}

export interface Script {
  name: string
  connectionId: string | null
  content: string
}

export interface ConstraintInfo {
  name: string
  /** PRIMARY KEY, UNIQUE, CHECK, FOREIGN KEY or EXCLUDE */
  type: string
  columns: string[]
  definition: string
}

export interface RoleInfo {
  name: string
  attributes: string[]
  memberOf: string[]
}

export interface DbeaverCandidate {
  id: string
  name: string
  driverId: string
  driverKnown: boolean
  host: string | null
  port: number | null
  database: string | null
  url: string | null
  user: string | null
  hasPassword: boolean
  folder: string | null
  type: ConnectionType
  readOnly: boolean
  ssh: boolean
  /** a connection with this name exists already */
  exists: boolean
}

export interface DbeaverPreview {
  dir: string
  connections: DbeaverCandidate[]
  warnings: string[]
}

export interface DriverLibrary {
  maven?: string | null
  file?: string | null
  optional?: boolean
}

/** What the driver manager saves (the server's DriverDescriptor). */
export interface DriverInput {
  id: string
  label: string
  className: string
  sampleUrl: string | null
  defaultPort: number | null
  defaultDatabase: string | null
  defaultUser: string | null
  dialect: string | null
  libraries: DriverLibrary[]
  categories?: string[]
  description?: string | null
}

export type TaskType = 'SQL' | 'EXPORT' | 'BACKUP'
export type ScheduleKind = 'MANUAL' | 'INTERVAL' | 'DAILY' | 'CRON'

export interface TaskRun {
  startedAt: string
  endedAt: string
  ok: boolean
  message: string
}

export interface Task {
  id: string | null
  name: string
  type: TaskType
  connectionId: string
  sql: string | null
  /** EXPORT / BACKUP target; {date}, {time} and {name} are replaced at run time */
  file: string | null
  /** EXPORT: CSV, TSV, JSON; BACKUP: CUSTOM, PLAIN */
  format: string | null
  schedule: { kind: ScheduleKind; everyMinutes: number | null; at: string | null; cron: string | null }
  enabled: boolean
  runs: TaskRun[]
}

export interface TaskView {
  task: Task
  running: boolean
  nextRun: string | null
}

export interface GoogleStatus {
  /** an OAuth client id is available (built in or saved by the user) */
  configured: boolean
  signedIn: boolean
  email?: string
  name?: string
  picture?: string | null
  sync: { enabled: boolean; lastSyncAt: number | null; lastError: string | null }
}

export interface SyncResult {
  connectionsChanged: number
  scriptsChanged: number
  pushed: boolean
  settingsChanged: boolean
  settings: Record<string, string>
  settingsUpdatedAt: number
  at: number
}

/** What a save to / import from Google Drive moves. */
export interface TransferKinds {
  connections: boolean
  scripts: boolean
  preferences: boolean
}

export interface TransferResult {
  direction: 'export' | 'import'
  connections: number
  scripts: number
  preferences: boolean
  uploaded: boolean
  settings: Record<string, string>
  settingsUpdatedAt: number
  at: number
}

export interface AppInfo {
  app: string
  version: string
  /** the installed app: it quits when the last tab is closed */
  desktop: boolean
}
