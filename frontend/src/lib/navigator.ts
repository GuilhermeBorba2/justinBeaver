import { api } from '@/api/client'
import type { DbObject, ObjectKind } from '@/api/types'
import { usePrefs } from '@/stores/prefs'

export type NodeKind = 'connection' | 'database' | 'container' | 'group' | 'table' | 'column' | 'object'

/** Immutable description of a tree row; children are loaded lazily by {@link loadChildren}. */
export interface NavDescriptor {
  key: string
  kind: NodeKind
  label: string
  /** Secondary text: column type, table kind... */
  detail?: string
  connectionId: string
  catalog?: string | null
  schema?: string | null
  table?: string
  /** Views and tables look different. */
  isView?: boolean
  primaryKey?: boolean
  /** for kind 'connection': PostgreSQL shows a Databases level */
  postgres?: boolean
  /** for kind 'database': the database the connection itself is on */
  current?: boolean
  /** for kind 'object': what it is, to fetch its source */
  objectKind?: ObjectKind
  system?: boolean
  /** Children known up front (groups carry the tables fetched for their container). */
  preloaded?: NavDescriptor[]
}

export const SYSTEM_CONTAINERS = /^(information_schema|pg_catalog|pg_toast.*|sys|mysql|performance_schema|sqlite_.*|system|pg_temp.*)$/i
const VIEW_TYPES = new Set(['VIEW', 'MATERIALIZED VIEW', 'SYSTEM VIEW'])
const TABLE_TYPES = new Set(['TABLE', 'BASE TABLE', 'PARTITIONED TABLE', 'TEMPORARY TABLE', 'GLOBAL TEMPORARY'])

export function isExpandable(node: NavDescriptor): boolean {
  return node.kind !== 'column' && node.kind !== 'object'
}

export async function loadChildren(node: NavDescriptor): Promise<NavDescriptor[]> {
  switch (node.kind) {
    case 'connection':
      return node.postgres ? loadDatabases(node) : loadContainers(node)
    case 'database':
      return loadSchemasOf(node)
    case 'container':
      return loadGroups(node)
    case 'group':
      return node.preloaded ?? []
    case 'table':
      return loadColumns(node)
    case 'column':
    case 'object':
      return []
  }
}

/** PostgreSQL: "Databases" folder; other databases get their own session key (`<id>::<db>`). */
async function loadDatabases(node: NavDescriptor): Promise<NavDescriptor[]> {
  const { current, databases } = await api.databases(node.connectionId)
  const nodes = databases.map<NavDescriptor>((name) => ({
    key: `${node.key}/db/${name}`,
    kind: 'database',
    label: name,
    connectionId: name === current ? node.connectionId : `${node.connectionId}::${name}`,
    current: name === current,
  }))
  return [{ key: `${node.key}/databases`, kind: 'group', label: 'Databases', detail: String(nodes.length), connectionId: node.connectionId, preloaded: nodes }]
}

async function loadSchemasOf(node: NavDescriptor): Promise<NavDescriptor[]> {
  const schemas = await loadContainers(node)
  if (schemas.some((s) => s.kind !== 'container')) return schemas // no schema level
  return [{ key: `${node.key}/schemas`, kind: 'group', label: 'Schemas', detail: String(schemas.length), connectionId: node.connectionId, preloaded: schemas }]
}

async function loadContainers(node: NavDescriptor): Promise<NavDescriptor[]> {
  const containers = await api.containers(node.connectionId)
  const nodes = containers.map<NavDescriptor>((c) => ({
    key: `${node.key}/${c.catalog ?? ''}.${c.schema ?? ''}`,
    kind: 'container',
    label: c.label,
    connectionId: node.connectionId,
    catalog: c.catalog,
    schema: c.schema,
    system: SYSTEM_CONTAINERS.test(c.label),
  }))
  nodes.sort((a, b) => Number(a.system) - Number(b.system) || a.label.localeCompare(b.label))
  // Preferences → "Hide system schemas" (information_schema, pg_catalog…), unless nothing else is left
  if (usePrefs().state.hideSystemObjects && nodes.some((n) => !n.system)) {
    nodes.splice(0, nodes.length, ...nodes.filter((n) => !n.system))
  }
  // A database without schemas/catalogs (SQLite): skip the pointless "(default)" level.
  if (nodes.length === 1 && containers[0]!.catalog === null && containers[0]!.schema === null) {
    return loadGroups(nodes[0]!)
  }
  return nodes
}

async function loadGroups(node: NavDescriptor): Promise<NavDescriptor[]> {
  const scope = { catalog: node.catalog, schema: node.schema }
  // routines/sequences/triggers are optional extras: a failure there must not hide the tables
  const [tables, objects] = await Promise.all([
    api.tables(node.connectionId, scope),
    api.objects(node.connectionId, scope).catch(() => null),
  ])
  const make = (name: string, type: string, remarks: string | null): NavDescriptor => ({
    key: `${node.key}/${type}/${name}`,
    kind: 'table',
    label: name,
    detail: remarks ?? undefined,
    connectionId: node.connectionId,
    catalog: node.catalog,
    schema: node.schema,
    table: name,
    isView: VIEW_TYPES.has(type),
  })
  const tableNodes = tables.filter((t) => TABLE_TYPES.has(t.type)).map((t) => make(t.name, t.type, t.remarks))
  const viewNodes = tables.filter((t) => VIEW_TYPES.has(t.type)).map((t) => make(t.name, t.type, t.remarks))
  const otherNodes = tables
    .filter((t) => !TABLE_TYPES.has(t.type) && !VIEW_TYPES.has(t.type))
    .map((t) => make(t.name, t.type, t.remarks))
  const group = (label: string, preloaded: NavDescriptor[]): NavDescriptor => ({
    key: `${node.key}/group/${label}`,
    kind: 'group',
    label,
    detail: String(preloaded.length),
    connectionId: node.connectionId,
    catalog: node.catalog,
    schema: node.schema,
    preloaded,
  })
  const objectNodes = (list: DbObject[] | undefined, kind: ObjectKind): NavDescriptor[] =>
    (list ?? []).map((o) => ({
      key: `${node.key}/${kind}/${o.name}`,
      kind: 'object',
      label: o.name,
      detail: o.detail ?? undefined,
      connectionId: node.connectionId,
      catalog: node.catalog,
      schema: node.schema,
      objectKind: kind,
    }))
  return [
    group('Tables', tableNodes),
    group('Views', viewNodes),
    ...(otherNodes.length ? [group('Other', otherNodes)] : []),
    group('Procedures', objectNodes(objects?.procedures, 'PROCEDURE')),
    group('Functions', objectNodes(objects?.functions, 'FUNCTION')),
    group('Sequences', objectNodes(objects?.sequences, 'SEQUENCE')),
    group('Triggers', objectNodes(objects?.triggers, 'TRIGGER')),
  ].filter((g) => g.preloaded!.length > 0 || g.label === 'Tables')
}

async function loadColumns(node: NavDescriptor): Promise<NavDescriptor[]> {
  const details = await api.tableDetails(node.connectionId, { catalog: node.catalog, schema: node.schema }, node.table!)
  return details.columns.map<NavDescriptor>((c) => ({
    key: `${node.key}/col/${c.name}`,
    kind: 'column',
    label: c.name,
    detail: c.size > 0 && c.size < 100000 ? `${c.typeName}(${c.size})` : c.typeName,
    connectionId: node.connectionId,
    primaryKey: c.primaryKey,
  }))
}
