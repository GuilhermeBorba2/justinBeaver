import type {
  CellContent,
  CellValue,
  Completion,
  Connection,
  ConstraintInfo,
  ConnectionInput,
  ConnectionTestResult,
  GoogleStatus,
  SyncResult,
  TransferKinds,
  TransferResult,
  AppInfo,
  Container,
  DataPage,
  DbInfo,
  DbeaverPreview,
  Driver,
  DriverInput,
  IsolationLevel,
  JobView,
  Metric,
  ObjectKind,
  Privilege,
  SchemaObjects,
  Script,
  ScriptInfo,
  EditResult,
  QueryResult,
  RoleInfo,
  RowChange,
  Scope,
  SearchHit,
  StatementResult,
  TableDetails,
  TableInfo,
  Task,
  TaskView,
  TransactionState,
} from './types'

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly sqlState?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const hasBody = body !== undefined
  const response = await fetch(`/api${path}`, {
    method,
    // The server rejects state-changing requests without this header (cross-site request protection).
    headers: { 'X-JB-Client': 'web', ...(hasBody ? { 'Content-Type': 'application/json' } : {}) },
    body: hasBody ? JSON.stringify(body) : undefined,
  })
  if (!response.ok) {
    let detail = response.statusText || `HTTP ${response.status}`
    let sqlState: string | undefined
    try {
      const problem = await response.json()
      detail = problem.detail ?? problem.message ?? detail
      sqlState = problem.sqlState ?? undefined
    } catch {
      // body was not JSON; keep the status text
    }
    throw new ApiError(detail, response.status, sqlState)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

function query(params: Record<string, string | number | boolean | null | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== '') {
      search.set(key, String(value))
    }
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}

const session = (id: string) => `/sessions/${encodeURIComponent(id)}`

export const api = {
  drivers: () => request<Driver[]>('GET', '/drivers'),

  connections: () => request<Connection[]>('GET', '/connections'),
  createConnection: (input: ConnectionInput) => request<Connection>('POST', '/connections', input),
  updateConnection: (id: string, input: ConnectionInput) =>
    request<Connection>('PUT', `/connections/${encodeURIComponent(id)}`, input),
  deleteConnection: (id: string) => request<void>('DELETE', `/connections/${encodeURIComponent(id)}`),
  connect: (id: string) => request<Connection>('POST', `/connections/${encodeURIComponent(id)}/connect`),
  disconnect: (id: string) => request<Connection>('POST', `/connections/${encodeURIComponent(id)}/disconnect`),
  testConnection: (input: ConnectionInput, savedId?: string) =>
    request<ConnectionTestResult>(
      'POST',
      `/connections/test${query({ savedId })}`,
      { ...input, driverId: input.driverId },
    ),

  databases: (id: string) => request<{ current: string | null; databases: string[] }>('GET', `${session(id)}/databases`),
  containers: (id: string) => request<Container[]>('GET', `${session(id)}/containers`),
  tables: (id: string, scope: Scope) =>
    request<TableInfo[]>('GET', `${session(id)}/tables${query({ catalog: scope.catalog, schema: scope.schema })}`),
  tableDetails: (id: string, scope: Scope, name: string) =>
    request<TableDetails>(
      'GET',
      `${session(id)}/table${query({ catalog: scope.catalog, schema: scope.schema, name })}`,
    ),
  tableData: (
    id: string,
    scope: Scope,
    table: string,
    options: { limit: number; offset: number; orderBy?: string | null; desc?: boolean; where?: string },
  ) =>
    request<DataPage>(
      'GET',
      `${session(id)}/data${query({
        catalog: scope.catalog,
        schema: scope.schema,
        table,
        limit: options.limit,
        offset: options.offset,
        orderBy: options.orderBy,
        desc: options.desc,
        where: options.where?.trim(),
      })}`,
    ),

  objects: (id: string, scope: Scope) =>
    request<SchemaObjects>('GET', `${session(id)}/objects${query({ catalog: scope.catalog, schema: scope.schema })}`),
  source: (id: string, scope: Scope, kind: ObjectKind, name: string) =>
    request<{ source: string }>('GET', `${session(id)}/source${query({ catalog: scope.catalog, schema: scope.schema, kind, name })}`),
  constraints: (id: string, scope: Scope, name: string) =>
    request<ConstraintInfo[]>('GET', `${session(id)}/constraints${query({ catalog: scope.catalog, schema: scope.schema, name })}`),
  roles: (id: string) => request<RoleInfo[]>('GET', `${session(id)}/roles`),
  privileges: (id: string, scope: Scope, name: string) =>
    request<Privilege[]>('GET', `${session(id)}/privileges${query({ catalog: scope.catalog, schema: scope.schema, name })}`),
  serverSessions: (id: string) => request<QueryResult>('GET', `${session(id)}/admin/sessions`),
  locks: (id: string) => request<QueryResult>('GET', `${session(id)}/admin/locks`),
  sessionAction: (id: string, sessionId: string, action: 'CANCEL' | 'TERMINATE') =>
    request<{ ok: boolean }>('POST', `${session(id)}/admin/session-action`, { id: sessionId, action }),
  metrics: (id: string) => request<Metric[]>('GET', `${session(id)}/admin/metrics`),
  backup: (id: string, file: string, format: 'CUSTOM' | 'PLAIN') =>
    request<JobView>('POST', `/connections/${encodeURIComponent(id)}/backup`, { file, format }),
  restore: (id: string, file: string) => request<JobView>('POST', `/connections/${encodeURIComponent(id)}/restore`, { file }),
  jobs: () => request<JobView[]>('GET', '/jobs'),
  job: (jobId: string) => request<JobView>('GET', `/jobs/${encodeURIComponent(jobId)}`),
  cancelJob: (jobId: string) => request<JobView>('POST', `/jobs/${encodeURIComponent(jobId)}/cancel`),
  info: (id: string) => request<DbInfo>('GET', `${session(id)}/info`),
  search: (id: string, text: string, limit = 50) => request<SearchHit[]>('GET', `${session(id)}/search${query({ q: text, limit })}`),
  cell: (id: string, scope: Scope, table: string, column: string, key: Record<string, CellValue>) =>
    request<CellContent>('POST', `${session(id)}/cell`, { ...scope, table, column, key }),
  ddl: (id: string, scope: Scope, name: string) =>
    request<{ ddl: string }>('GET', `${session(id)}/ddl${query({ catalog: scope.catalog, schema: scope.schema, name })}`),
  count: (id: string, scope: Scope, table: string, where?: string) =>
    request<{ count: number }>(
      'GET',
      `${session(id)}/count${query({ catalog: scope.catalog, schema: scope.schema, table, where: where?.trim() })}`,
    ),
  completion: (id: string) => request<Completion>('GET', `${session(id)}/completion`),
  explain: (id: string, sql: string, maxRows: number) =>
    request<QueryResult>('POST', `${session(id)}/explain`, { sql, maxRows }),

  previewEdits: (id: string, scope: Scope, table: string, changes: RowChange[]) =>
    request<{ statements: string[] }>('POST', `${session(id)}/edits/preview`, { ...scope, table, changes }),
  applyEdits: (id: string, scope: Scope, table: string, changes: RowChange[]) =>
    request<EditResult>('POST', `${session(id)}/edits/apply`, { ...scope, table, changes }),

  runQuery: (id: string, sql: string, maxRows: number) =>
    request<QueryResult>('POST', `${session(id)}/query`, { sql, maxRows }),
  runScript: (id: string, sql: string, maxRows: number) =>
    request<StatementResult[]>('POST', `${session(id)}/script`, { sql, maxRows, stopOnError: true }),
  cancel: (id: string) => request<{ cancelled: boolean }>('POST', `${session(id)}/cancel`),

  autoCommit: (id: string) => request<TransactionState>('GET', `${session(id)}/transaction`),
  setIsolation: (id: string, level: IsolationLevel) =>
    request<TransactionState>('POST', `${session(id)}/isolation`, { level }),
  context: (id: string) => request<Scope>('GET', `${session(id)}/context`),
  setContext: (id: string, scope: Scope) => request<Scope>('POST', `${session(id)}/context`, scope),
  setAutoCommit: (id: string, value: boolean) =>
    request<{ autoCommit: boolean }>('POST', `${session(id)}/autocommit`, { value }),
  commit: (id: string) => request<{ ok: boolean }>('POST', `${session(id)}/commit`),
  rollback: (id: string) => request<{ ok: boolean }>('POST', `${session(id)}/rollback`),

  dbeaverPreview: (dir?: string) => request<DbeaverPreview>('GET', `/connections/import/dbeaver${query({ dir })}`),
  dbeaverImport: (dir: string, ids: string[]) =>
    request<{ created: string[]; skipped: { name: string; reason: string }[] }>('POST', '/connections/import/dbeaver', { dir, ids }),
  customDrivers: () => request<string[]>('GET', '/drivers/custom'),
  saveCustomDriver: (driver: DriverInput) => request<Driver>('PUT', `/drivers/custom/${encodeURIComponent(driver.id)}`, driver),
  deleteCustomDriver: (id: string) => request<void>('DELETE', `/drivers/custom/${encodeURIComponent(id)}`),

  appInfo: () => request<AppInfo>('GET', '/app/info'),
  quitApp: () => request<void>('POST', '/app/quit'),
  googleStatus: () => request<GoogleStatus>('GET', '/google/status'),
  googleLogin: () => request<{ url: string }>('POST', '/google/login'),
  googleLogout: () => request<void>('POST', '/google/logout'),
  syncCloudCopy: () => request<{ exists: boolean }>('GET', '/sync/cloud'),
  syncEnable: (passphrase: string) => request<{ cloudCopyExisted: boolean }>('POST', '/sync/enable', { passphrase }),
  syncNow: (settings?: Record<string, string>, settingsUpdatedAt?: number) =>
    request<SyncResult>('POST', '/sync/now', { settings, settingsUpdatedAt }),
  syncExport: (kinds: TransferKinds, settings?: Record<string, string>, settingsUpdatedAt?: number) =>
    request<TransferResult>('POST', '/sync/export', { ...kinds, settings, settingsUpdatedAt }),
  syncImport: (kinds: TransferKinds) => request<TransferResult>('POST', '/sync/import', { ...kinds }),
  syncDisable: () => request<void>('DELETE', '/sync'),
  syncDeleteCloud: () => request<void>('DELETE', '/sync/cloud'),

  tasks: () => request<TaskView[]>('GET', '/tasks'),
  saveTask: (task: Task) =>
    task.id ? request<TaskView>('PUT', `/tasks/${encodeURIComponent(task.id)}`, task) : request<TaskView>('POST', '/tasks', task),
  deleteTask: (id: string) => request<void>('DELETE', `/tasks/${encodeURIComponent(id)}`),
  runTask: (id: string) => request<void>('POST', `/tasks/${encodeURIComponent(id)}/run`),

  scripts: () => request<ScriptInfo[]>('GET', '/scripts'),
  script: (name: string) => request<Script>('GET', `/scripts/${encodeURIComponent(name)}`),
  createScript: (connectionId: string | null, content: string) =>
    request<Script>('POST', '/scripts', { connectionId, content }),
  saveScript: (name: string, connectionId: string | null, content: string) =>
    request<ScriptInfo>('PUT', `/scripts/${encodeURIComponent(name)}`, { connectionId, content }),
  renameScript: (name: string, newName: string) =>
    request<ScriptInfo>('POST', `/scripts/${encodeURIComponent(name)}/rename`, { name: newName }),
  deleteScript: (name: string) => request<void>('DELETE', `/scripts/${encodeURIComponent(name)}`),
  /** undefined when nothing was saved yet */
  workspace: () => request<unknown>('GET', '/workspace'),
  saveWorkspace: (document: unknown) => request<void>('PUT', '/workspace', document),
}
