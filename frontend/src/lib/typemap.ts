import type { ColumnInfo } from '@/api/types'
import type { Dialect } from './ddl'

/**
 * Column type for a table created on another database (DBeaver's data transfer "target type"): the source
 * type is reduced to a family, then written the target's way. Unknown types are copied as they are.
 */
type Family = 'int' | 'smallint' | 'bigint' | 'decimal' | 'float' | 'double' | 'bool' | 'char' | 'varchar' | 'text'
  | 'date' | 'time' | 'timestamp' | 'timestamptz' | 'binary' | 'uuid' | 'json'

function family(typeName: string): Family | null {
  const t = typeName.toLowerCase().replace(/\(.*\)/, '').replace(/\s+unsigned$/, '').trim()
  if (/^(int|int4|integer|mediumint|serial|serial4)$/.test(t)) return 'int'
  if (/^(smallint|int2|tinyint|smallserial|serial2)$/.test(t)) return 'smallint'
  if (/^(bigint|int8|bigserial|serial8)$/.test(t)) return 'bigint'
  if (/^(numeric|decimal|number|money|smallmoney|dec)$/.test(t)) return 'decimal'
  if (/^(real|float4|float)$/.test(t)) return 'float'
  if (/^(double|double precision|float8|binary_double)$/.test(t)) return 'double'
  if (/^(bool|boolean|bit)$/.test(t)) return 'bool'
  if (/^(char|character|nchar|bpchar)$/.test(t)) return 'char'
  if (/^(varchar|character varying|nvarchar|varchar2|nvarchar2|string)$/.test(t)) return 'varchar'
  if (/^(text|clob|nclob|longtext|mediumtext|tinytext|ntext|citext)$/.test(t)) return 'text'
  if (t === 'date') return 'date'
  if (/^(time|time without time zone|timetz|time with time zone)$/.test(t)) return 'time'
  if (/^(timestamp|timestamp without time zone|datetime|datetime2|smalldatetime)$/.test(t)) return 'timestamp'
  if (/^(timestamptz|timestamp with time zone|datetimeoffset)$/.test(t)) return 'timestamptz'
  if (/^(bytea|blob|longblob|mediumblob|varbinary|binary|image|raw|long raw)$/.test(t)) return 'binary'
  if (/^(uuid|uniqueidentifier)$/.test(t)) return 'uuid'
  if (/^(json|jsonb)$/.test(t)) return 'json'
  return null
}

export function targetType(column: Pick<ColumnInfo, 'typeName' | 'size' | 'decimalDigits'>, dialect: Dialect, sameDialect: boolean): string {
  const sized = column.size > 0 && column.size < 100000
  if (sameDialect) {
    const raw = column.typeName
    const needsSize = sized && /char|binary|decimal|numeric/i.test(raw) && !raw.includes('(') && !/text|bpchar$/i.test(raw)
    return needsSize ? `${raw}(${column.size}${column.decimalDigits ? `,${column.decimalDigits}` : ''})` : raw
  }
  const f = family(column.typeName)
  const size = sized ? column.size : 255
  const precision = sized ? `(${column.size},${column.decimalDigits ?? 0})` : ''
  switch (f) {
    case null:
      return column.typeName
    case 'int':
      return dialect === 'oracle' ? 'NUMBER(10)' : 'INTEGER'
    case 'smallint':
      return dialect === 'oracle' ? 'NUMBER(5)' : 'SMALLINT'
    case 'bigint':
      return dialect === 'oracle' ? 'NUMBER(19)' : 'BIGINT'
    case 'decimal':
      return dialect === 'oracle' ? `NUMBER${precision}` : `DECIMAL${precision || '(38,10)'}`
    case 'float':
      return dialect === 'oracle' ? 'BINARY_FLOAT' : 'REAL'
    case 'double':
      return dialect === 'postgresql' ? 'DOUBLE PRECISION' : dialect === 'sqlserver' ? 'FLOAT' : dialect === 'oracle' ? 'BINARY_DOUBLE' : 'DOUBLE'
    case 'bool':
      return dialect === 'sqlserver' ? 'BIT' : dialect === 'oracle' ? 'NUMBER(1)' : 'BOOLEAN'
    case 'char':
      return dialect === 'sqlserver' ? `NCHAR(${size})` : `CHAR(${size})`
    case 'varchar':
      return dialect === 'sqlserver' ? `NVARCHAR(${size})` : dialect === 'oracle' ? `VARCHAR2(${Math.min(size, 4000)})` : `VARCHAR(${size})`
    case 'text':
      return dialect === 'mysql' || dialect === 'mariadb' ? 'LONGTEXT' : dialect === 'sqlserver' ? 'NVARCHAR(MAX)' : dialect === 'oracle' ? 'CLOB' : 'TEXT'
    case 'date':
      return 'DATE'
    case 'time':
      return dialect === 'oracle' ? 'VARCHAR2(20)' : 'TIME'
    case 'timestamp':
      return dialect === 'mysql' || dialect === 'mariadb' ? 'DATETIME(6)' : dialect === 'sqlserver' ? 'DATETIME2' : 'TIMESTAMP'
    case 'timestamptz':
      return dialect === 'postgresql' ? 'TIMESTAMPTZ' : dialect === 'sqlserver' ? 'DATETIMEOFFSET' : dialect === 'oracle' ? 'TIMESTAMP WITH TIME ZONE'
        : dialect === 'mysql' || dialect === 'mariadb' ? 'DATETIME(6)' : 'TIMESTAMP'
    case 'binary':
      return dialect === 'postgresql' ? 'BYTEA' : dialect === 'sqlserver' ? 'VARBINARY(MAX)' : 'BLOB'
    case 'uuid':
      return dialect === 'postgresql' ? 'UUID' : dialect === 'sqlserver' ? 'UNIQUEIDENTIFIER' : dialect === 'oracle' ? 'RAW(16)' : 'CHAR(36)'
    case 'json':
      return dialect === 'postgresql' ? 'JSONB' : dialect === 'mysql' || dialect === 'mariadb' ? 'JSON' : dialect === 'sqlserver' ? 'NVARCHAR(MAX)'
        : dialect === 'oracle' ? 'CLOB' : 'TEXT'
  }
}
