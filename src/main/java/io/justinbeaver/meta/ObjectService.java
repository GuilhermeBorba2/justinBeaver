package io.justinbeaver.meta;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.justinbeaver.query.DataService;
import io.justinbeaver.session.DbSession;
import org.springframework.stereotype.Service;

/**
 * Non-table objects of a schema (procedures, functions, sequences, triggers), their source, and table
 * privileges. Routines come from the portable JDBC calls; sequences, triggers and source need
 * per-database catalog queries, each tried defensively (a missing view or privilege yields nothing).
 */
@Service
public class ObjectService {

    public record DbObject(String name, String detail) {
    }

    public record Objects(List<DbObject> procedures, List<DbObject> functions, List<DbObject> sequences,
                          List<DbObject> triggers) {
    }

    public record Privilege(String grantor, String grantee, String privilege, String grantable) {
    }

    public enum Kind { PROCEDURE, FUNCTION, SEQUENCE, TRIGGER }

    /**
     * @param type       PRIMARY KEY, UNIQUE, CHECK, FOREIGN KEY or EXCLUDE
     * @param columns    the constrained columns, in key order (empty for a table-level CHECK)
     * @param definition the constraint as the database prints it, e.g. {@code CHECK (total >= 0)}
     */
    public record ConstraintInfo(String name, String type, List<String> columns, String definition) {
    }

    /** A login or group role (PostgreSQL), a user account (MySQL), a server principal (SQL Server). */
    public record RoleInfo(String name, List<String> attributes, List<String> memberOf) {
    }

    private final MetadataService metadata;

    public ObjectService(MetadataService metadata) {
        this.metadata = metadata;
    }

    public Objects objects(DbSession session, String catalog, String schema) throws SQLException {
        return session.call(conn -> {
            DatabaseMetaData meta = conn.getMetaData();
            String cat = MetadataService.blankToNull(catalog);
            String sch = MetadataService.blankToNull(schema);
            String dialect = MetadataService.dialectOf(meta.getDatabaseProductName());
            List<DbObject> functions = functions(meta, cat, sch);
            Set<String> functionNames = new LinkedHashSet<>();
            functions.forEach(f -> functionNames.add(f.name()));
            List<DbObject> procedures = procedures(meta, cat, sch).stream()
                    .filter(p -> !functionNames.contains(p.name())).toList();
            return new Objects(procedures, functions, sequences(conn, dialect, cat, sch), triggers(conn, dialect, cat, sch));
        });
    }

    private static List<DbObject> functions(DatabaseMetaData meta, String catalog, String schema) {
        Map<String, DbObject> out = new LinkedHashMap<>();
        try (ResultSet rs = meta.getFunctions(catalog, MetadataService.escapePattern(meta, schema), "%")) {
            while (rs.next()) {
                String name = rs.getString("FUNCTION_NAME");
                out.putIfAbsent(name, new DbObject(name, rs.getString("REMARKS")));
            }
        } catch (SQLException | AbstractMethodError | UnsupportedOperationException ignored) {
            // driver without getFunctions
        }
        return List.copyOf(out.values());
    }

    private static List<DbObject> procedures(DatabaseMetaData meta, String catalog, String schema) {
        Map<String, DbObject> out = new LinkedHashMap<>();
        try (ResultSet rs = meta.getProcedures(catalog, MetadataService.escapePattern(meta, schema), "%")) {
            while (rs.next()) {
                String name = rs.getString("PROCEDURE_NAME");
                // some drivers append ";1" overload suffixes (SQL Server)
                int semicolon = name.indexOf(';');
                name = semicolon > 0 ? name.substring(0, semicolon) : name;
                out.putIfAbsent(name, new DbObject(name, rs.getString("REMARKS")));
            }
        } catch (SQLException | UnsupportedOperationException ignored) {
            // driver without getProcedures
        }
        return List.copyOf(out.values());
    }

    /** Schema for INFORMATION_SCHEMA filters: MySQL puts databases in the catalog. */
    private static String owner(String catalog, String schema) {
        return schema != null ? schema : catalog;
    }

    private static List<DbObject> sequences(Connection conn, String dialect, String catalog, String schema) {
        String owner = owner(catalog, schema);
        return switch (dialect) {
            case "sqlserver" -> list(conn, "SELECT name, NULL FROM sys.sequences WHERE SCHEMA_NAME(schema_id) = ?", owner);
            case "oracle" -> list(conn, "SELECT SEQUENCE_NAME, NULL FROM ALL_SEQUENCES WHERE SEQUENCE_OWNER = ?", owner);
            case "mysql", "sqlite" -> List.of();
            case "mariadb" -> list(conn, "SELECT TABLE_NAME, NULL FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE = 'SEQUENCE' AND TABLE_SCHEMA = ?", owner);
            default -> list(conn, "SELECT SEQUENCE_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = ? ORDER BY SEQUENCE_NAME", owner);
        };
    }

    private static List<DbObject> triggers(Connection conn, String dialect, String catalog, String schema) {
        String owner = owner(catalog, schema);
        List<DbObject> rows = switch (dialect) {
            case "sqlite" -> list(conn, "SELECT name, tbl_name FROM sqlite_master WHERE type = 'trigger' ORDER BY name", null);
            case "sqlserver" -> list(conn, "SELECT t.name, OBJECT_NAME(t.parent_id) FROM sys.triggers t JOIN sys.objects o "
                    + "ON t.object_id = o.object_id WHERE SCHEMA_NAME(o.schema_id) = ? ORDER BY t.name", owner);
            case "oracle" -> list(conn, "SELECT TRIGGER_NAME, TABLE_NAME FROM ALL_TRIGGERS WHERE OWNER = ? ORDER BY TRIGGER_NAME", owner);
            default -> list(conn, "SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE FROM INFORMATION_SCHEMA.TRIGGERS "
                    + "WHERE TRIGGER_SCHEMA = ? ORDER BY TRIGGER_NAME", owner);
        };
        // PostgreSQL lists one row per event of the same trigger
        Map<String, DbObject> unique = new LinkedHashMap<>();
        rows.forEach(r -> unique.putIfAbsent(r.name(), new DbObject(r.name(), r.detail() == null ? null : "on " + r.detail())));
        return List.copyOf(unique.values());
    }

    private static List<DbObject> list(Connection conn, String sql, String param) {
        List<DbObject> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (param != null) {
                ps.setString(1, param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DbObject(rs.getString(1), rs.getString(2)));
                }
            }
        } catch (SQLException ignored) {
            // catalog view missing or not readable on this database
        }
        return out;
    }

    /** Source of a routine, trigger or sequence, for the SQL editor. */
    public String source(DbSession session, String catalog, String schema, Kind kind, String name) throws SQLException {
        return session.call(conn -> {
            DatabaseMetaData meta = conn.getMetaData();
            String cat = MetadataService.blankToNull(catalog);
            String sch = MetadataService.blankToNull(schema);
            String dialect = MetadataService.dialectOf(meta.getDatabaseProductName());
            String quote = meta.getIdentifierQuoteString();
            String qualified = DataService.qualified(meta, quote, cat, sch, name);
            String owner = owner(cat, sch);
            String source = switch (kind) {
                case FUNCTION, PROCEDURE -> routineSource(conn, dialect, kind, owner, name, qualified);
                case TRIGGER -> triggerSource(conn, dialect, owner, name, qualified);
                case SEQUENCE -> sequenceSource(conn, dialect, owner, name, qualified);
            };
            return source != null ? source
                    : "-- The source of %s %s is not available through this driver.".formatted(kind.name().toLowerCase(), qualified);
        });
    }

    private static String routineSource(Connection conn, String dialect, Kind kind, String owner, String name, String qualified) {
        return switch (dialect) {
            case "postgresql" -> joined(conn, "SELECT pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                    + "WHERE n.nspname = ? AND p.proname = ?", owner, name, ";\n\n");
            case "mysql", "mariadb" -> showCreate(conn, "SHOW CREATE " + (kind == Kind.FUNCTION ? "FUNCTION " : "PROCEDURE ") + qualified, 3);
            case "sqlserver" -> joined(conn, "SELECT OBJECT_DEFINITION(OBJECT_ID(QUOTENAME(?) + '.' + QUOTENAME(?)))", owner, name, "\n");
            case "oracle" -> joined(conn, "SELECT TEXT FROM ALL_SOURCE WHERE OWNER = ? AND NAME = ? ORDER BY TYPE, LINE", owner, name, "");
            default -> joined(conn, "SELECT ROUTINE_DEFINITION FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_SCHEMA = ? AND ROUTINE_NAME = ?",
                    owner, name, "\n\n");
        };
    }

    private static String triggerSource(Connection conn, String dialect, String owner, String name, String qualified) {
        return switch (dialect) {
            case "sqlite" -> joined(conn, "SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = ?", name, null, "\n");
            case "mysql", "mariadb" -> showCreate(conn, "SHOW CREATE TRIGGER " + qualified, 3);
            case "postgresql" -> joined(conn, "SELECT pg_get_triggerdef(t.oid, true) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                    + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND t.tgname = ?", owner, name, ";\n");
            case "sqlserver" -> joined(conn, "SELECT OBJECT_DEFINITION(OBJECT_ID(QUOTENAME(?) + '.' + QUOTENAME(?)))", owner, name, "\n");
            case "oracle" -> joined(conn, "SELECT TRIGGER_BODY FROM ALL_TRIGGERS WHERE OWNER = ? AND TRIGGER_NAME = ?", owner, name, "\n");
            default -> joined(conn, "SELECT ACTION_STATEMENT FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA = ? AND TRIGGER_NAME = ?",
                    owner, name, "\n");
        };
    }

    private static String sequenceSource(Connection conn, String dialect, String owner, String name, String qualified) {
        if (dialect.equals("sqlserver") || dialect.equals("oracle") || dialect.equals("mariadb")) {
            return "CREATE SEQUENCE " + qualified + ";";
        }
        String sql = "SELECT START_VALUE, INCREMENT, MINIMUM_VALUE, MAXIMUM_VALUE FROM INFORMATION_SCHEMA.SEQUENCES "
                + "WHERE SEQUENCE_SCHEMA = ? AND SEQUENCE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return "CREATE SEQUENCE %s\n  START WITH %s\n  INCREMENT BY %s\n  MINVALUE %s\n  MAXVALUE %s;".formatted(qualified,
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
                }
            }
        } catch (SQLException ignored) {
            // fall through
        }
        return "CREATE SEQUENCE " + qualified + ";";
    }

    private static String joined(Connection conn, String sql, String first, String second, String separator) {
        List<String> parts = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, first);
            if (second != null) {
                ps.setString(2, second);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getString(1) != null) {
                        parts.add(rs.getString(1));
                    }
                }
            }
        } catch (SQLException ignored) {
            return null;
        }
        return parts.isEmpty() ? null : String.join(separator, parts).strip();
    }

    private static String showCreate(Connection conn, String sql, int column) {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(column) : null;
        } catch (SQLException ignored) {
            return null;
        }
    }

    public List<Privilege> privileges(DbSession session, String catalog, String schema, String table) throws SQLException {
        return session.call(conn -> {
            String cat = MetadataService.blankToNull(catalog);
            String sch = MetadataService.blankToNull(schema);
            if (!metadata.tableExists(conn, cat, sch, table)) {
                throw new IllegalArgumentException("Table '%s' not found".formatted(table));
            }
            DatabaseMetaData meta = conn.getMetaData();
            List<Privilege> out = new ArrayList<>();
            try (ResultSet rs = meta.getTablePrivileges(cat, MetadataService.escapePattern(meta, sch),
                    MetadataService.escapePattern(meta, table))) {
                while (rs.next()) {
                    out.add(new Privilege(rs.getString("GRANTOR"), rs.getString("GRANTEE"), rs.getString("PRIVILEGE"),
                            rs.getString("IS_GRANTABLE")));
                }
            } catch (SQLException | UnsupportedOperationException e) {
                // not supported by this driver: empty
            }
            return out;
        });
    }

    /** Constraints of one table; empty when the database exposes none through its catalog (SQLite). */
    public List<ConstraintInfo> constraints(DbSession session, String catalog, String schema, String table) throws SQLException {
        return session.call(conn -> {
            String cat = MetadataService.blankToNull(catalog);
            String sch = MetadataService.blankToNull(schema);
            if (!metadata.tableExists(conn, cat, sch, table)) {
                throw new IllegalArgumentException("Table '%s' not found".formatted(table));
            }
            String dialect = MetadataService.dialectOf(conn.getMetaData().getDatabaseProductName());
            return switch (dialect) {
                case "postgresql" -> postgresConstraints(conn, sch == null ? "public" : sch, table);
                case "sqlite" -> List.of();
                default -> standardConstraints(conn, owner(cat, sch), table);
            };
        });
    }

    private static List<ConstraintInfo> postgresConstraints(Connection conn, String schema, String table) throws SQLException {
        String sql = """
                SELECT c.conname, c.contype, pg_get_constraintdef(c.oid, true),
                       array_to_string(ARRAY(SELECT a.attname FROM unnest(c.conkey) WITH ORDINALITY k(num, ord)
                                             JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.num
                                             ORDER BY k.ord), chr(1))
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = ? AND t.relname = ?
                ORDER BY CASE c.contype WHEN 'p' THEN 0 WHEN 'u' THEN 1 WHEN 'f' THEN 2 WHEN 'c' THEN 3 ELSE 4 END, c.conname""";
        List<ConstraintInfo> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = switch (rs.getString(2)) {
                        case "p" -> "PRIMARY KEY";
                        case "u" -> "UNIQUE";
                        case "f" -> "FOREIGN KEY";
                        case "c" -> "CHECK";
                        case "x" -> "EXCLUDE";
                        default -> rs.getString(2);
                    };
                    String columns = rs.getString(4);
                    out.add(new ConstraintInfo(rs.getString(1), type,
                            columns == null || columns.isEmpty() ? List.of() : List.of(columns.split("\u0001")), rs.getString(3)));
                }
            }
        }
        return out;
    }

    /** INFORMATION_SCHEMA (MySQL 8, MariaDB, SQL Server, H2, Oracle has none: empty). */
    private static List<ConstraintInfo> standardConstraints(Connection conn, String owner, String table) {
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, String> checks = new LinkedHashMap<>();
        Map<String, List<String>> columns = new LinkedHashMap<>();
        String filter = " WHERE tc.TABLE_NAME = ?" + (owner != null ? " AND tc.TABLE_SCHEMA = ?" : "");
        try (PreparedStatement ps = conn.prepareStatement("SELECT tc.CONSTRAINT_NAME, tc.CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc"
                + filter + " ORDER BY tc.CONSTRAINT_TYPE, tc.CONSTRAINT_NAME")) {
            bindTable(ps, table, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    types.put(rs.getString(1), rs.getString(2));
                }
            }
        } catch (SQLException e) {
            return List.of();
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT tc.CONSTRAINT_NAME, tc.COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE tc"
                + filter + " ORDER BY tc.CONSTRAINT_NAME, tc.ORDINAL_POSITION")) {
            bindTable(ps, table, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                }
            }
        } catch (SQLException ignored) {
            // no column usage view: constraints without columns
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT cc.CONSTRAINT_NAME, cc.CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc"
                + (owner != null ? " WHERE cc.CONSTRAINT_SCHEMA = ?" : ""))) {
            if (owner != null) {
                ps.setString(1, owner);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    checks.put(rs.getString(1), rs.getString(2));
                }
            }
        } catch (SQLException ignored) {
            // MySQL before 8.0.16 has no CHECK_CONSTRAINTS
        }
        List<ConstraintInfo> out = new ArrayList<>();
        types.forEach((name, type) -> {
            List<String> cols = columns.getOrDefault(name, List.of());
            String definition = "CHECK".equals(type)
                    ? "CHECK (" + checks.getOrDefault(name, "?") + ")"
                    : type + " (" + String.join(", ", cols) + ")";
            out.add(new ConstraintInfo(name, type, cols, definition));
        });
        return out;
    }

    private static void bindTable(PreparedStatement ps, String table, String owner) throws SQLException {
        ps.setString(1, table);
        if (owner != null) {
            ps.setString(2, owner);
        }
    }

    /** Roles / users of the server; empty when the database has no catalog for them or access is denied. */
    public List<RoleInfo> roles(DbSession session) throws SQLException {
        return session.call(conn -> {
            String dialect = MetadataService.dialectOf(conn.getMetaData().getDatabaseProductName());
            List<RoleInfo> out = new ArrayList<>();
            String sql = switch (dialect) {
                case "postgresql" -> """
                        SELECT r.rolname,
                               concat_ws(',', CASE WHEN r.rolcanlogin THEN 'LOGIN' END, CASE WHEN r.rolsuper THEN 'SUPERUSER' END,
                                         CASE WHEN r.rolcreatedb THEN 'CREATEDB' END, CASE WHEN r.rolcreaterole THEN 'CREATEROLE' END,
                                         CASE WHEN r.rolreplication THEN 'REPLICATION' END,
                                         CASE WHEN r.rolconnlimit >= 0 THEN 'CONNECTION LIMIT ' || r.rolconnlimit END,
                                         CASE WHEN r.rolvaliduntil IS NOT NULL THEN 'VALID UNTIL ' || r.rolvaliduntil END),
                               array_to_string(ARRAY(SELECT g.rolname FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid
                                                     WHERE m.member = r.oid ORDER BY 1), ',')
                        FROM pg_roles r WHERE r.rolname NOT LIKE 'pg\\_%' ORDER BY r.rolname""";
                case "mysql", "mariadb" -> "SELECT CONCAT(QUOTE(User), '@', QUOTE(Host)), CONCAT_WS(',', IF(account_locked = 'Y', 'LOCKED', NULL)), '' "
                        + "FROM mysql.user ORDER BY User, Host";
                case "sqlserver" -> "SELECT name, type_desc + CASE WHEN is_disabled = 1 THEN ',DISABLED' ELSE '' END, '' "
                        + "FROM sys.server_principals WHERE type IN ('S', 'U', 'G', 'R') AND name NOT LIKE '##%' ORDER BY name";
                case "oracle" -> "SELECT USERNAME, ACCOUNT_STATUS, '' FROM DBA_USERS ORDER BY USERNAME";
                default -> null;
            };
            if (sql == null) {
                return out;
            }
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    out.add(new RoleInfo(rs.getString(1), split(rs.getString(2)), split(rs.getString(3))));
                }
            } catch (SQLException e) {
                if (dialect.equals("mysql") || dialect.equals("mariadb")) {
                    // older servers without account_locked
                    try (Statement st = conn.createStatement();
                         ResultSet rs = st.executeQuery("SELECT CONCAT(QUOTE(User), '@', QUOTE(Host)) FROM mysql.user ORDER BY User, Host")) {
                        while (rs.next()) {
                            out.add(new RoleInfo(rs.getString(1), List.of(), List.of()));
                        }
                        return out;
                    } catch (SQLException ignored) {
                        // no access to mysql.user
                    }
                }
                throw e;
            }
            return out;
        });
    }

    private static List<String> split(String joined) {
        return joined == null || joined.isBlank() ? List.of() : List.of(joined.split(","));
    }
}
