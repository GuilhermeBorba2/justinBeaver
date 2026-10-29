/**
 * DDL statements for schema changes made from the UI (DBeaver's "Create/Edit/Delete object"),
 * per dialect. Every statement is shown to the user before it runs.
 */
export type Dialect = 'postgresql' | 'mysql' | 'mariadb' | 'sqlite' | 'h2' | 'sqlserver' | 'oracle' | 'generic'

export interface DdlContext {
  dialect: Dialect
  /** identifier quote from the driver; '' when quoting is unsupported */
  quote: string
  supportsCatalogs: boolean
  supportsSchemas: boolean
}

export interface TableRef {
  catalog: string | null
  schema: string | null
  name: string
}

export interface ColumnSpec {
  name: string
  type: string
  nullable: boolean
  /** raw SQL expression, e.g. `0`, `'n/a'`, `CURRENT_TIMESTAMP`; empty/null = no default */
  defaultValue?: string | null
  primaryKey?: boolean
}

export type ReferentialAction = '' | 'CASCADE' | 'SET NULL' | 'SET DEFAULT' | 'RESTRICT' | 'NO ACTION'

/** The database cannot do this in place; the message says what to do instead. */
export class UnsupportedDdl extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'UnsupportedDdl'
  }
}

export function q(ctx: DdlContext, name: string): string {
  if (!ctx.quote) return name
  const close = ctx.quote === '[' ? ']' : ctx.quote
  return ctx.quote + name.replaceAll(close, close + close) + close
}

export function qualified(ctx: DdlContext, table: TableRef): string {
  const parts: string[] = []
  if (table.catalog && ctx.supportsCatalogs) parts.push(q(ctx, table.catalog))
  if (table.schema && ctx.supportsSchemas) parts.push(q(ctx, table.schema))
  parts.push(q(ctx, table.name))
  return parts.join('.')
}

function hasDefault(c: ColumnSpec): boolean {
  return c.defaultValue !== undefined && c.defaultValue !== null && c.defaultValue.trim() !== ''
}

function requireName(kind: string, value: string): string {
  const name = value.trim()
  if (!name) throw new UnsupportedDdl(`${kind} name is required`)
  return name
}

export function columnDefinition(ctx: DdlContext, c: ColumnSpec): string {
  let sql = `${q(ctx, requireName('Column', c.name))} ${requireName('Type of ' + c.name, c.type)}`
  if (hasDefault(c)) sql += ` DEFAULT ${c.defaultValue!.trim()}`
  if (!c.nullable) sql += ' NOT NULL'
  return sql
}

export function createTable(ctx: DdlContext, table: TableRef, columns: ColumnSpec[]): string[] {
  requireName('Table', table.name)
  if (columns.length === 0) throw new UnsupportedDdl('A table needs at least one column')
  const lines = columns.map((c) => `  ${columnDefinition(ctx, c)}`)
  const pk = columns.filter((c) => c.primaryKey).map((c) => q(ctx, c.name))
  if (pk.length) lines.push(`  PRIMARY KEY (${pk.join(', ')})`)
  return [`CREATE TABLE ${qualified(ctx, table)} (\n${lines.join(',\n')}\n)`]
}

export function addColumn(ctx: DdlContext, table: TableRef, c: ColumnSpec): string[] {
  const t = qualified(ctx, table)
  const def = columnDefinition(ctx, c)
  switch (ctx.dialect) {
    case 'sqlserver':
      return [`ALTER TABLE ${t} ADD ${def}`]
    case 'oracle':
      return [`ALTER TABLE ${t} ADD (${def})`]
    default:
      return [`ALTER TABLE ${t} ADD COLUMN ${def}`]
  }
}

export function dropColumn(ctx: DdlContext, table: TableRef, column: string): string[] {
  return [`ALTER TABLE ${qualified(ctx, table)} DROP COLUMN ${q(ctx, column)}`]
}

/** sp_rename takes the object name as a string literal. */
function sqlServerName(table: TableRef, column?: string): string {
  const parts = [table.schema, table.name, column].filter(Boolean) as string[]
  return `'${parts.map((p) => `[${p.replaceAll(']', ']]')}]`).join('.').replaceAll("'", "''")}'`
}

const literal = (value: string) => `'${value.replaceAll("'", "''")}'`

export function renameColumn(ctx: DdlContext, table: TableRef, from: string, to: string): string[] {
  requireName('New column', to)
  if (ctx.dialect === 'sqlserver') {
    return [`EXEC sp_rename ${sqlServerName(table, from)}, ${literal(to)}, 'COLUMN'`]
  }
  return [`ALTER TABLE ${qualified(ctx, table)} RENAME COLUMN ${q(ctx, from)} TO ${q(ctx, to)}`]
}

/** Changes from `before` to `after` (name, type, nullability, default), in as few statements as the dialect allows. */
export function alterColumn(ctx: DdlContext, table: TableRef, before: ColumnSpec, after: ColumnSpec): string[] {
  const t = qualified(ctx, table)
  const statements: string[] = []
  const renamed = after.name.trim() !== before.name
  if (renamed) statements.push(...renameColumn(ctx, table, before.name, after.name.trim()))
  const name = q(ctx, renamed ? after.name.trim() : before.name)
  const typeChanged = after.type.trim() !== before.type.trim()
  const nullChanged = after.nullable !== before.nullable
  const defaultChanged = (after.defaultValue ?? '').trim() !== (before.defaultValue ?? '').trim()
  if (!typeChanged && !nullChanged && !defaultChanged) return statements

  switch (ctx.dialect) {
    case 'sqlite':
      throw new UnsupportedDdl(
        'SQLite cannot change a column type, nullability or default in place: create a new table, copy the data, and swap the tables',
      )
    case 'mysql':
    case 'mariadb':
      statements.push(`ALTER TABLE ${t} MODIFY COLUMN ${columnDefinition(ctx, { ...after, name: renamed ? after.name.trim() : before.name })}`)
      return statements
    case 'oracle': {
      let def = `${name} ${after.type.trim()}`
      if (defaultChanged) def += hasDefault(after) ? ` DEFAULT ${after.defaultValue!.trim()}` : ' DEFAULT NULL'
      if (nullChanged) def += after.nullable ? ' NULL' : ' NOT NULL'
      statements.push(`ALTER TABLE ${t} MODIFY (${def})`)
      return statements
    }
    case 'sqlserver':
      if (defaultChanged) {
        throw new UnsupportedDdl('SQL Server keeps defaults in named constraints: change them with ALTER TABLE … DROP/ADD CONSTRAINT')
      }
      statements.push(`ALTER TABLE ${t} ALTER COLUMN ${name} ${after.type.trim()}${after.nullable ? ' NULL' : ' NOT NULL'}`)
      return statements
    default: {
      // PostgreSQL, H2 and standard SQL: one ALTER COLUMN per property
      if (typeChanged) {
        const typeClause = ctx.dialect === 'postgresql' ? 'TYPE' : 'SET DATA TYPE'
        statements.push(`ALTER TABLE ${t} ALTER COLUMN ${name} ${typeClause} ${after.type.trim()}`)
      }
      if (nullChanged) {
        const allowNull = ctx.dialect === 'h2' ? 'SET NULL' : 'DROP NOT NULL'
        statements.push(`ALTER TABLE ${t} ALTER COLUMN ${name} ${after.nullable ? allowNull : 'SET NOT NULL'}`)
      }
      if (defaultChanged) {
        statements.push(
          hasDefault(after)
            ? `ALTER TABLE ${t} ALTER COLUMN ${name} SET DEFAULT ${after.defaultValue!.trim()}`
            : `ALTER TABLE ${t} ALTER COLUMN ${name} DROP DEFAULT`,
        )
      }
      return statements
    }
  }
}

export function renameTable(ctx: DdlContext, table: TableRef, to: string): string[] {
  requireName('New table', to)
  switch (ctx.dialect) {
    case 'sqlserver':
      return [`EXEC sp_rename ${sqlServerName(table)}, ${literal(to)}`]
    case 'mysql':
    case 'mariadb':
      return [`RENAME TABLE ${qualified(ctx, table)} TO ${qualified(ctx, { ...table, name: to })}`]
    default:
      // the new name stays in the same schema and is given unqualified
      return [`ALTER TABLE ${qualified(ctx, table)} RENAME TO ${q(ctx, to)}`]
  }
}

export function supportsCascade(ctx: DdlContext): boolean {
  return ctx.dialect === 'postgresql' || ctx.dialect === 'h2' || ctx.dialect === 'oracle'
}

export function dropTable(ctx: DdlContext, table: TableRef, isView: boolean, cascade: boolean): string[] {
  const suffix = cascade && supportsCascade(ctx) ? (ctx.dialect === 'oracle' && !isView ? ' CASCADE CONSTRAINTS' : ' CASCADE') : ''
  return [`DROP ${isView ? 'VIEW' : 'TABLE'} ${qualified(ctx, table)}${suffix}`]
}

export function truncateTable(ctx: DdlContext, table: TableRef): string[] {
  return ctx.dialect === 'sqlite' ? [`DELETE FROM ${qualified(ctx, table)}`] : [`TRUNCATE TABLE ${qualified(ctx, table)}`]
}

export function createIndex(ctx: DdlContext, table: TableRef, name: string, columns: string[], unique: boolean): string[] {
  requireName('Index', name)
  if (columns.length === 0) throw new UnsupportedDdl('Pick at least one column for the index')
  return [
    `CREATE ${unique ? 'UNIQUE ' : ''}INDEX ${q(ctx, name.trim())} ON ${qualified(ctx, table)} (${columns.map((c) => q(ctx, c)).join(', ')})`,
  ]
}

export function dropIndex(ctx: DdlContext, table: TableRef, name: string): string[] {
  switch (ctx.dialect) {
    case 'mysql':
    case 'mariadb':
    case 'sqlserver':
      return [`DROP INDEX ${q(ctx, name)} ON ${qualified(ctx, table)}`]
    default: {
      // indexes live in the table's schema
      const schema = table.schema && ctx.supportsSchemas ? `${q(ctx, table.schema)}.` : ''
      return [`DROP INDEX ${schema}${q(ctx, name)}`]
    }
  }
}

export function addForeignKey(
  ctx: DdlContext,
  table: TableRef,
  name: string,
  columns: string[],
  target: TableRef,
  targetColumns: string[],
  onDelete: ReferentialAction,
  onUpdate: ReferentialAction,
): string[] {
  if (ctx.dialect === 'sqlite') {
    throw new UnsupportedDdl('SQLite cannot add a foreign key to an existing table: declare it in CREATE TABLE')
  }
  if (columns.length === 0 || columns.length !== targetColumns.length) {
    throw new UnsupportedDdl('Pick the same number of local and referenced columns')
  }
  if (ctx.dialect === 'oracle' && onUpdate) {
    throw new UnsupportedDdl('Oracle has no ON UPDATE actions for foreign keys')
  }
  let sql = `ALTER TABLE ${qualified(ctx, table)} ADD ${name.trim() ? `CONSTRAINT ${q(ctx, name.trim())} ` : ''}FOREIGN KEY (${columns
    .map((c) => q(ctx, c))
    .join(', ')}) REFERENCES ${qualified(ctx, target)} (${targetColumns.map((c) => q(ctx, c)).join(', ')})`
  if (onDelete) sql += ` ON DELETE ${onDelete}`
  if (onUpdate) sql += ` ON UPDATE ${onUpdate}`
  return [sql]
}

export function dropForeignKey(ctx: DdlContext, table: TableRef, name: string): string[] {
  switch (ctx.dialect) {
    case 'sqlite':
      throw new UnsupportedDdl('SQLite cannot drop a foreign key from an existing table: recreate the table')
    case 'mysql':
    case 'mariadb':
      return [`ALTER TABLE ${qualified(ctx, table)} DROP FOREIGN KEY ${q(ctx, name)}`]
    default:
      return [`ALTER TABLE ${qualified(ctx, table)} DROP CONSTRAINT ${q(ctx, name)}`]
  }
}

export function addCheck(ctx: DdlContext, table: TableRef, name: string, expression: string): string[] {
  if (ctx.dialect === 'sqlite') throw new UnsupportedDdl('SQLite cannot add a CHECK to an existing table: declare it in CREATE TABLE')
  if (!expression.trim()) throw new UnsupportedDdl('Write the condition the rows must satisfy, e.g. total >= 0')
  return [`ALTER TABLE ${qualified(ctx, table)} ADD ${name.trim() ? `CONSTRAINT ${q(ctx, name.trim())} ` : ''}CHECK (${expression.trim()})`]
}

export function addUnique(ctx: DdlContext, table: TableRef, name: string, columns: string[]): string[] {
  if (columns.length === 0) throw new UnsupportedDdl('Pick at least one column')
  const list = columns.map((c) => q(ctx, c)).join(', ')
  if (ctx.dialect === 'sqlite') {
    requireName('Unique constraint', name)
    return [`CREATE UNIQUE INDEX ${q(ctx, name.trim())} ON ${qualified(ctx, table)} (${list})`]
  }
  return [`ALTER TABLE ${qualified(ctx, table)} ADD ${name.trim() ? `CONSTRAINT ${q(ctx, name.trim())} ` : ''}UNIQUE (${list})`]
}

/** Drop a CHECK / UNIQUE / PRIMARY KEY constraint by name (`type` as the database reports it). */
export function dropConstraint(ctx: DdlContext, table: TableRef, name: string, type: string): string[] {
  const t = qualified(ctx, table)
  switch (ctx.dialect) {
    case 'sqlite':
      throw new UnsupportedDdl('SQLite cannot drop a constraint from an existing table: recreate the table')
    case 'mysql':
    case 'mariadb':
      if (type === 'PRIMARY KEY') return [`ALTER TABLE ${t} DROP PRIMARY KEY`]
      if (type === 'UNIQUE') return [`ALTER TABLE ${t} DROP INDEX ${q(ctx, name)}`]
      if (type === 'FOREIGN KEY') return [`ALTER TABLE ${t} DROP FOREIGN KEY ${q(ctx, name)}`]
      return [`ALTER TABLE ${t} DROP ${ctx.dialect === 'mysql' ? 'CHECK' : 'CONSTRAINT'} ${q(ctx, name)}`]
    default:
      return [`ALTER TABLE ${t} DROP CONSTRAINT ${q(ctx, name)}`]
  }
}

export type Maintenance = 'vacuum' | 'vacuum-full' | 'analyze' | 'reindex' | 'optimize' | 'check' | 'repair' | 'rebuild-indexes'

/** The maintenance tasks this database offers for a table (DBeaver's "Tools" menu of a table). */
export function maintenanceTasks(dialect: Dialect): { id: Maintenance; label: string }[] {
  switch (dialect) {
    case 'postgresql':
      return [
        { id: 'vacuum', label: 'Vacuum (VACUUM ANALYZE)' },
        { id: 'vacuum-full', label: 'Vacuum full (rewrites the table, locks it)' },
        { id: 'analyze', label: 'Analyze (refresh statistics)' },
        { id: 'reindex', label: 'Reindex' },
      ]
    case 'mysql':
    case 'mariadb':
      return [
        { id: 'optimize', label: 'Optimize' },
        { id: 'analyze', label: 'Analyze' },
        { id: 'check', label: 'Check' },
        { id: 'repair', label: 'Repair' },
      ]
    case 'sqlite':
      return [
        { id: 'vacuum', label: 'Vacuum (whole database)' },
        { id: 'analyze', label: 'Analyze' },
        { id: 'reindex', label: 'Reindex' },
      ]
    case 'sqlserver':
      return [
        { id: 'analyze', label: 'Update statistics' },
        { id: 'rebuild-indexes', label: 'Rebuild indexes' },
      ]
    case 'oracle':
      return [{ id: 'analyze', label: 'Gather statistics' }]
    case 'h2':
      return [{ id: 'analyze', label: 'Analyze' }]
    default:
      return []
  }
}

export function maintenance(ctx: DdlContext, table: TableRef, task: Maintenance): string[] {
  const t = qualified(ctx, table)
  switch (ctx.dialect) {
    case 'postgresql':
      return [{ vacuum: `VACUUM ANALYZE ${t}`, 'vacuum-full': `VACUUM FULL ANALYZE ${t}`, analyze: `ANALYZE ${t}`, reindex: `REINDEX TABLE ${t}` }[
        task as 'vacuum'
      ] ?? unsupported(task)]
    case 'mysql':
    case 'mariadb':
      return [{ optimize: `OPTIMIZE TABLE ${t}`, analyze: `ANALYZE TABLE ${t}`, check: `CHECK TABLE ${t}`, repair: `REPAIR TABLE ${t}` }[
        task as 'optimize'
      ] ?? unsupported(task)]
    case 'sqlite':
      return [{ vacuum: 'VACUUM', analyze: `ANALYZE ${t}`, reindex: `REINDEX ${t}` }[task as 'vacuum'] ?? unsupported(task)]
    case 'sqlserver':
      return [{ analyze: `UPDATE STATISTICS ${t}`, 'rebuild-indexes': `ALTER INDEX ALL ON ${t} REBUILD` }[task as 'analyze'] ?? unsupported(task)]
    case 'oracle': {
      if (task !== 'analyze') return [unsupported(task)]
      const owner = table.schema ? `'${table.schema.replaceAll("'", "''")}'` : 'USER'
      return [`BEGIN DBMS_STATS.GATHER_TABLE_STATS(${owner}, '${table.name.replaceAll("'", "''")}'); END;`]
    }
    case 'h2':
      return task === 'analyze' ? [`ANALYZE TABLE ${t}`] : [unsupported(task)]
    default:
      return [unsupported(task)]
  }
}

/** MySQL lists accounts as `'user'@'host'` already quoted; a bare name gets `@'%'`. */
function mysqlAccount(name: string): string {
  return /^'.*'@'.*'$/.test(name) ? name : `${literal(name)}@'%'`
}

export function createUser(ctx: DdlContext, name: string, password: string): string[] {
  requireName('User', name)
  const n = name.trim()
  switch (ctx.dialect) {
    case 'postgresql':
      return [`CREATE ROLE ${q(ctx, n)} LOGIN${password ? ` PASSWORD ${literal(password)}` : ''}`]
    case 'mysql':
    case 'mariadb':
      return [`CREATE USER ${mysqlAccount(n)}${password ? ` IDENTIFIED BY ${literal(password)}` : ''}`]
    case 'sqlserver':
      return [`CREATE LOGIN ${q(ctx, n)} WITH PASSWORD = ${literal(password)}`]
    case 'oracle':
      return [`CREATE USER ${q(ctx, n)} IDENTIFIED BY ${q({ ...ctx, quote: '"' }, password)}`]
    default:
      throw new UnsupportedDdl('Creating users is not supported for this database')
  }
}

export function dropUser(ctx: DdlContext, name: string): string[] {
  switch (ctx.dialect) {
    case 'postgresql':
      return [`DROP ROLE ${q(ctx, name)}`]
    case 'mysql':
    case 'mariadb':
      return [`DROP USER ${mysqlAccount(name)}`]
    case 'sqlserver':
      return [`DROP LOGIN ${q(ctx, name)}`]
    case 'oracle':
      return [`DROP USER ${q(ctx, name)}`]
    default:
      throw new UnsupportedDdl('Dropping users is not supported for this database')
  }
}

export function changePassword(ctx: DdlContext, name: string, password: string): string[] {
  switch (ctx.dialect) {
    case 'postgresql':
      return [`ALTER ROLE ${q(ctx, name)} PASSWORD ${literal(password)}`]
    case 'mysql':
    case 'mariadb':
      return [`ALTER USER ${mysqlAccount(name)} IDENTIFIED BY ${literal(password)}`]
    case 'sqlserver':
      return [`ALTER LOGIN ${q(ctx, name)} WITH PASSWORD = ${literal(password)}`]
    case 'oracle':
      return [`ALTER USER ${q(ctx, name)} IDENTIFIED BY ${q({ ...ctx, quote: '"' }, password)}`]
    default:
      throw new UnsupportedDdl('Changing passwords is not supported for this database')
  }
}

/** Make `member` a member of `role` (PostgreSQL / SQL Server server roles / MySQL 8 roles). */
export function grantRole(ctx: DdlContext, role: string, member: string): string[] {
  switch (ctx.dialect) {
    case 'postgresql':
      return [`GRANT ${q(ctx, role)} TO ${q(ctx, member)}`]
    case 'mysql':
    case 'mariadb':
      return [`GRANT ${mysqlAccount(role)} TO ${mysqlAccount(member)}`]
    case 'sqlserver':
      return [`ALTER SERVER ROLE ${q(ctx, role)} ADD MEMBER ${q(ctx, member)}`]
    case 'oracle':
      return [`GRANT ${q(ctx, role)} TO ${q(ctx, member)}`]
    default:
      throw new UnsupportedDdl('Roles are not supported for this database')
  }
}

function unsupported(task: string): never {
  throw new UnsupportedDdl(`"${task}" is not available on this database`)
}

/** Common types offered in the type field (free text is allowed). */
export function typeSuggestions(dialect: Dialect): string[] {
  switch (dialect) {
    case 'postgresql':
      return ['integer', 'bigint', 'serial', 'bigserial', 'numeric(12,2)', 'real', 'double precision', 'boolean', 'text',
        'varchar(255)', 'char(1)', 'date', 'time', 'timestamp', 'timestamptz', 'uuid', 'jsonb', 'json', 'bytea', 'inet']
    case 'mysql':
    case 'mariadb':
      return ['INT', 'BIGINT', 'INT AUTO_INCREMENT', 'DECIMAL(12,2)', 'DOUBLE', 'BOOLEAN', 'VARCHAR(255)', 'CHAR(1)', 'TEXT',
        'LONGTEXT', 'DATE', 'TIME', 'DATETIME', 'TIMESTAMP', 'JSON', 'BLOB', 'ENUM(\'a\',\'b\')']
    case 'sqlite':
      return ['INTEGER', 'REAL', 'TEXT', 'BLOB', 'NUMERIC', 'DATE', 'DATETIME', 'BOOLEAN']
    case 'sqlserver':
      return ['INT', 'BIGINT', 'INT IDENTITY(1,1)', 'DECIMAL(12,2)', 'FLOAT', 'BIT', 'NVARCHAR(255)', 'NVARCHAR(MAX)',
        'VARCHAR(255)', 'DATE', 'TIME', 'DATETIME2', 'DATETIMEOFFSET', 'UNIQUEIDENTIFIER', 'VARBINARY(MAX)']
    case 'oracle':
      return ['NUMBER(10)', 'NUMBER(12,2)', 'VARCHAR2(255)', 'NVARCHAR2(255)', 'CHAR(1)', 'CLOB', 'BLOB', 'DATE', 'TIMESTAMP',
        'TIMESTAMP WITH TIME ZONE']
    default:
      return ['INTEGER', 'BIGINT', 'DECIMAL(12,2)', 'DOUBLE', 'BOOLEAN', 'VARCHAR(255)', 'CHAR(1)', 'CLOB', 'DATE', 'TIME',
        'TIMESTAMP', 'BLOB']
  }
}
