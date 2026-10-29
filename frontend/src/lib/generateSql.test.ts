import { describe, expect, it } from 'vitest'
import type { ColumnInfo } from '@/api/types'
import type { DdlContext, TableRef } from './ddl'
import { deleteRow, insertInto, paramName, selectAll, selectColumns, updateRow } from './generateSql'

const ctx: DdlContext = { dialect: 'postgresql', quote: '"', supportsCatalogs: false, supportsSchemas: true }
const table: TableRef = { catalog: null, schema: 'public', name: 'person' }

function column(name: string, extra: Partial<ColumnInfo> = {}): ColumnInfo {
  return {
    name,
    typeName: 'text',
    size: 0,
    decimalDigits: null,
    nullable: true,
    defaultValue: null,
    position: 1,
    primaryKey: false,
    autoIncrement: false,
    remarks: null,
    ...extra,
  }
}

const columns = [column('id', { primaryKey: true, autoIncrement: true }), column('full name'), column('age')]

describe('generated SQL', () => {
  it('selects with a qualified, quoted name', () => {
    expect(selectAll(ctx, table)).toBe('SELECT *\nFROM "public"."person";\n')
    expect(selectColumns(ctx, table, columns)).toContain('"full name"')
  })

  it('inserts every column but generated keys, using bind variables', () => {
    expect(insertInto(ctx, table, columns)).toBe(
      'INSERT INTO "public"."person"\n  ("full name", "age")\nVALUES\n  (:full_name, :age);\n',
    )
  })

  it('updates non-key columns by primary key', () => {
    const sql = updateRow(ctx, table, columns)
    expect(sql).toContain('SET "full name" = :full_name')
    expect(sql).toContain('WHERE "id" = :id')
    expect(sql).not.toContain('SET "id"')
  })

  it('deletes by primary key, or leaves a placeholder condition without one', () => {
    expect(deleteRow(ctx, table, columns)).toContain('WHERE "id" = :id')
    expect(deleteRow(ctx, table, [column('a')])).toContain('WHERE /* condition */')
  })

  it('makes bind-variable names of word characters', () => {
    expect(paramName('order-id')).toBe('order_id')
    expect(paramName('1st')).toBe('p_1st')
    expect(paramName('!!!')).toBe('value')
  })
})
