import { inject, type InjectionKey, type Ref } from 'vue'
import { api } from '@/api/client'
import type { ColumnInfo } from '@/api/types'
import { UnsupportedDdl, type DdlContext, type TableRef } from '@/lib/ddl'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'
import type EditPreviewDialog from '@/components/EditPreviewDialog.vue'
import type SchemaObjectDialog from '@/components/SchemaObjectDialog.vue'
import type { Mode as DialogMode } from '@/components/SchemaObjectDialog.vue'

/** App-wide entry points for schema changes ("Create / Edit / Delete object"). */
export interface SchemaService {
  context(connectionId: string): Promise<DdlContext>
  createTable(connectionId: string, catalog: string | null, schema: string | null): Promise<boolean>
  editColumn(connectionId: string, table: TableRef, columns: ColumnInfo[], column?: ColumnInfo): Promise<boolean>
  createIndex(connectionId: string, table: TableRef, columns: ColumnInfo[]): Promise<boolean>
  addForeignKey(connectionId: string, table: TableRef, columns: ColumnInfo[]): Promise<boolean>
  /** Build statements (UnsupportedDdl becomes a toast), preview, run. */
  run(connectionId: string, title: string, build: (ctx: DdlContext) => string[], danger?: boolean): Promise<boolean>
  /** Build statements without running them (the table editor queues them); null when they cannot be built. */
  build(connectionId: string, build: (ctx: DdlContext) => string[]): Promise<string[] | null>
  /** Open a form and return its statements without running them; null when cancelled. */
  form(
    connectionId: string,
    mode: Exclude<DialogMode, 'table'>,
    table: TableRef,
    columns: ColumnInfo[],
    column?: ColumnInfo,
  ): Promise<string[] | null>
  /** Preview and run statements built elsewhere. */
  execute(connectionId: string, title: string, statements: string[], danger?: boolean): Promise<boolean>
}

export const SCHEMA_SERVICE: InjectionKey<SchemaService> = Symbol('schema-service')

export function useSchemaService(): SchemaService {
  const service = inject(SCHEMA_SERVICE)
  if (!service) throw new Error('Schema service not provided')
  return service
}

export function createSchemaService(
  objectDialog: Ref<InstanceType<typeof SchemaObjectDialog> | null>,
  preview: Ref<InstanceType<typeof EditPreviewDialog> | null>,
): SchemaService {
  const connections = useConnections()
  const ui = useUi()

  async function context(connectionId: string): Promise<DdlContext> {
    const info = await connections.dbInfo(connectionId)
    return {
      dialect: info.dialect,
      quote: info.identifierQuote,
      supportsCatalogs: info.supportsCatalogs,
      supportsSchemas: info.supportsSchemas,
    }
  }

  async function execute(connectionId: string, title: string, statements: string[], danger: boolean): Promise<boolean> {
    const production = connections.isProduction(connectionId)
    const shownTitle = production ? `PRODUCTION · ${title}` : title
    if (!(await preview.value?.confirmDdl(statements, shownTitle, danger || production))) return false
    try {
      const results = await api.runScript(connectionId, statements.join(';\n'), 10)
      connections.schemaChanged(connectionId)
      const failed = results.find((r) => r.error !== null)
      if (failed) {
        const done = results.filter((r) => r.error === null).length
        ui.error(`${done} of ${statements.length} statement(s) ran; failed on: ${failed.sql}\n${failed.error}`)
        return false
      }
      ui.info(`${title}: done`)
      return true
    } catch (e) {
      ui.error(e instanceof Error ? e.message : String(e))
      return false
    }
  }

  async function run(connectionId: string, title: string, build: (ctx: DdlContext) => string[], danger = false) {
    let statements: string[]
    try {
      statements = build(await context(connectionId))
    } catch (e) {
      ui.error(e instanceof UnsupportedDdl || e instanceof Error ? e.message : String(e))
      return false
    }
    return execute(connectionId, title, statements, danger)
  }

  async function fromDialog(
    connectionId: string,
    title: string,
    open: (ctx: DdlContext) => Promise<string[] | null | undefined>,
  ): Promise<boolean> {
    const ctx = await ui.guard(() => context(connectionId))
    if (!ctx) return false
    const statements = await open(ctx)
    return statements ? execute(connectionId, title, statements, false) : false
  }

  async function build(connectionId: string, make: (ctx: DdlContext) => string[]) {
    try {
      return make(await context(connectionId))
    } catch (e) {
      ui.error(e instanceof UnsupportedDdl || e instanceof Error ? e.message : String(e))
      return null
    }
  }

  async function form(connectionId: string, mode: Exclude<DialogMode, 'table'>, table: TableRef, columns: ColumnInfo[], column?: ColumnInfo) {
    const ctx = await ui.guard(() => context(connectionId))
    if (!ctx) return null
    return (await objectDialog.value!.open(mode, { ctx, table, columns, column })) ?? null
  }

  return {
    context,
    run,
    build,
    form,
    execute: (connectionId, title, statements, danger = false) => execute(connectionId, title, statements, danger),
    createTable(connectionId, catalog, schema) {
      return fromDialog(connectionId, 'Create table', (ctx) =>
        objectDialog.value!.open('table', { ctx, table: { catalog, schema, name: '' } }),
      )
    },
    editColumn(connectionId, table, columns, column) {
      return fromDialog(connectionId, column ? `Edit column ${column.name}` : 'Add column', (ctx) =>
        objectDialog.value!.open('column', { ctx, table, columns, column }),
      )
    },
    createIndex(connectionId, table, columns) {
      return fromDialog(connectionId, 'Create index', (ctx) => objectDialog.value!.open('index', { ctx, table, columns }))
    },
    addForeignKey(connectionId, table, columns) {
      return fromDialog(connectionId, 'Add foreign key', (ctx) => objectDialog.value!.open('foreignKey', { ctx, table, columns }))
    },
  }
}
