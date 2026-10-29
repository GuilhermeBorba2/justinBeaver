/** Mirrors the server's read-only check: statements that start like a query. */
const READ = /^\s*(?:(?:--[^\n]*\n|\/\*[\s\S]*?\*\/)\s*)*(select|with|show|explain|describe|desc|pragma|values|table)\b/i

export function looksReadOnly(sql: string): boolean {
  return READ.test(sql)
}

/** Statements that would change something, for the production confirmation. */
export function writingStatements(statements: string[]): string[] {
  return statements.filter((s) => !looksReadOnly(s))
}
