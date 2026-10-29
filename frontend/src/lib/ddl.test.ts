import { describe, expect, it } from 'vitest'
import {
  addCheck,
  addColumn,
  addUnique,
  changePassword,
  createUser,
  dropConstraint,
  dropUser,
  grantRole,
  maintenance,
  addForeignKey,
  alterColumn,
  createIndex,
  createTable,
  dropForeignKey,
  dropIndex,
  dropTable,
  renameColumn,
  renameTable,
  truncateTable,
  UnsupportedDdl,
  type DdlContext,
  type Dialect,
  type TableRef,
} from './ddl'

const ctx = (dialect: Dialect, quote = '"'): DdlContext => ({ dialect, quote, supportsCatalogs: false, supportsSchemas: true })
const pg = ctx('postgresql')
const mysql: DdlContext = { dialect: 'mysql', quote: '`', supportsCatalogs: true, supportsSchemas: false }
const table: TableRef = { catalog: 'shop', schema: 'public', name: 'item' }
const col = { name: 'price', type: 'numeric(10,2)', nullable: true, defaultValue: '' }

describe('ddl', () => {
  it('creates a table with a composite primary key', () => {
    expect(
      createTable(pg, table, [
        { name: 'id', type: 'integer', nullable: false, primaryKey: true },
        { name: 'line', type: 'integer', nullable: false, primaryKey: true },
        { name: 'note', type: 'text', nullable: true, defaultValue: "'n/a'" },
      ]),
    ).toEqual([
      'CREATE TABLE "public"."item" (\n  "id" integer NOT NULL,\n  "line" integer NOT NULL,\n  "note" text DEFAULT \'n/a\',\n  PRIMARY KEY ("id", "line")\n)',
    ])
  })

  it('qualifies by catalog on MySQL and escapes quotes in names', () => {
    expect(addColumn(mysql, table, { name: 'we`ird', type: 'INT', nullable: false })).toEqual([
      'ALTER TABLE `shop`.`item` ADD COLUMN `we``ird` INT NOT NULL',
    ])
  })

  it('uses each dialect\'s ADD syntax', () => {
    expect(addColumn(ctx('sqlserver', '['), table, col)[0]).toBe('ALTER TABLE [public].[item] ADD [price] numeric(10,2)')
    expect(addColumn(ctx('oracle'), table, col)[0]).toBe('ALTER TABLE "public"."item" ADD ("price" numeric(10,2))')
  })

  it('alters a PostgreSQL column one property at a time', () => {
    expect(alterColumn(pg, table, col, { name: 'cost', type: 'numeric(12,2)', nullable: false, defaultValue: '0' })).toEqual([
      'ALTER TABLE "public"."item" RENAME COLUMN "price" TO "cost"',
      'ALTER TABLE "public"."item" ALTER COLUMN "cost" TYPE numeric(12,2)',
      'ALTER TABLE "public"."item" ALTER COLUMN "cost" SET NOT NULL',
      'ALTER TABLE "public"."item" ALTER COLUMN "cost" SET DEFAULT 0',
    ])
  })

  it('uses MODIFY COLUMN on MySQL and SET DATA TYPE / SET NULL on H2', () => {
    expect(alterColumn(mysql, table, col, { ...col, nullable: false })).toEqual([
      'ALTER TABLE `shop`.`item` MODIFY COLUMN `price` numeric(10,2) NOT NULL',
    ])
    expect(alterColumn(ctx('h2'), table, { ...col, nullable: false }, { ...col, type: 'decimal(20,2)' })).toEqual([
      'ALTER TABLE "public"."item" ALTER COLUMN "price" SET DATA TYPE decimal(20,2)',
      'ALTER TABLE "public"."item" ALTER COLUMN "price" SET NULL',
    ])
  })

  it('refuses what the database cannot do in place', () => {
    expect(() => alterColumn(ctx('sqlite'), table, col, { ...col, type: 'TEXT' })).toThrow(UnsupportedDdl)
    expect(alterColumn(ctx('sqlite'), table, col, { ...col, name: 'cost' })).toEqual([
      'ALTER TABLE "public"."item" RENAME COLUMN "price" TO "cost"',
    ])
    expect(() => addForeignKey(ctx('sqlite'), table, 'fk', ['a'], table, ['b'], '', '')).toThrow(/CREATE TABLE/)
  })

  it('renames per dialect', () => {
    expect(renameTable(pg, table, 'article')).toEqual(['ALTER TABLE "public"."item" RENAME TO "article"'])
    expect(renameTable(mysql, table, 'article')).toEqual(['RENAME TABLE `shop`.`item` TO `shop`.`article`'])
    expect(renameTable(ctx('sqlserver', '['), table, "o'neil")).toEqual(["EXEC sp_rename '[public].[item]', 'o''neil'"])
    expect(renameColumn(ctx('sqlserver', '['), table, 'a', 'b')).toEqual(["EXEC sp_rename '[public].[item].[a]', 'b', 'COLUMN'"])
  })

  it('drops and truncates', () => {
    expect(dropTable(pg, table, false, true)).toEqual(['DROP TABLE "public"."item" CASCADE'])
    expect(dropTable(mysql, table, true, true)).toEqual(['DROP VIEW `shop`.`item`'])
    expect(truncateTable(ctx('sqlite'), table)).toEqual(['DELETE FROM "public"."item"'])
  })

  it('creates and drops indexes', () => {
    expect(createIndex(pg, table, 'idx_price', ['price', 'id'], true)).toEqual([
      'CREATE UNIQUE INDEX "idx_price" ON "public"."item" ("price", "id")',
    ])
    expect(dropIndex(pg, table, 'idx_price')).toEqual(['DROP INDEX "public"."idx_price"'])
    expect(dropIndex(mysql, table, 'idx_price')).toEqual(['DROP INDEX `idx_price` ON `shop`.`item`'])
  })

  it('adds and drops foreign keys', () => {
    const owner: TableRef = { catalog: null, schema: 'public', name: 'owner' }
    expect(addForeignKey(pg, table, 'fk_owner', ['owner_id'], owner, ['id'], 'CASCADE', '')).toEqual([
      'ALTER TABLE "public"."item" ADD CONSTRAINT "fk_owner" FOREIGN KEY ("owner_id") REFERENCES "public"."owner" ("id") ON DELETE CASCADE',
    ])
    expect(dropForeignKey(mysql, table, 'fk_owner')).toEqual(['ALTER TABLE `shop`.`item` DROP FOREIGN KEY `fk_owner`'])
    expect(dropForeignKey(pg, table, 'fk_owner')).toEqual(['ALTER TABLE "public"."item" DROP CONSTRAINT "fk_owner"'])
  })

  it('requires names', () => {
    expect(() => createTable(pg, { ...table, name: ' ' }, [col])).toThrow(/Table name/)
    expect(() => createIndex(pg, table, 'i', [], false)).toThrow(/at least one column/)
  })

  it('adds and drops constraints per dialect', () => {
    expect(addCheck(pg, table, 'ck_price', 'price >= 0')).toEqual(['ALTER TABLE "public"."item" ADD CONSTRAINT "ck_price" CHECK (price >= 0)'])
    expect(addUnique(mysql, table, '', ['a', 'b'])).toEqual(['ALTER TABLE `shop`.`item` ADD UNIQUE (`a`, `b`)'])
    expect(dropConstraint(mysql, table, 'uq', 'UNIQUE')).toEqual(['ALTER TABLE `shop`.`item` DROP INDEX `uq`'])
    expect(dropConstraint(mysql, table, 'ck', 'CHECK')).toEqual(['ALTER TABLE `shop`.`item` DROP CHECK `ck`'])
    expect(dropConstraint(pg, table, 'ck', 'CHECK')).toEqual(['ALTER TABLE "public"."item" DROP CONSTRAINT "ck"'])
    expect(() => addCheck(ctx('sqlite'), table, '', 'x > 0')).toThrow(UnsupportedDdl)
  })

  it('builds maintenance statements', () => {
    expect(maintenance(pg, table, 'vacuum')).toEqual(['VACUUM ANALYZE "public"."item"'])
    expect(maintenance(mysql, table, 'optimize')).toEqual(['OPTIMIZE TABLE `shop`.`item`'])
    expect(() => maintenance(pg, table, 'optimize')).toThrow(UnsupportedDdl)
  })

  it('manages users', () => {
    expect(createUser(pg, 'app', "it's")).toEqual(['CREATE ROLE "app" LOGIN PASSWORD \'it\'\'s\''])
    expect(createUser(mysql, 'app', 'pw')).toEqual(["CREATE USER 'app'@'%' IDENTIFIED BY 'pw'"])
    expect(dropUser(mysql, "'app'@'localhost'")).toEqual(["DROP USER 'app'@'localhost'"])
    expect(changePassword(pg, 'app', 'x')).toEqual(['ALTER ROLE "app" PASSWORD \'x\''])
    expect(grantRole(pg, 'readers', 'app')).toEqual(['GRANT "readers" TO "app"'])
  })
})
