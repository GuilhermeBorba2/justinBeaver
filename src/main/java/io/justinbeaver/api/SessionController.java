package io.justinbeaver.api;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import io.justinbeaver.edit.EditModels.CellContent;
import io.justinbeaver.edit.EditModels.CellRequest;
import io.justinbeaver.edit.EditModels.EditRequest;
import io.justinbeaver.edit.EditModels.EditResult;
import io.justinbeaver.edit.EditModels.PreviewResult;
import io.justinbeaver.edit.EditService;
import io.justinbeaver.meta.DdlService;
import io.justinbeaver.meta.ObjectService;
import io.justinbeaver.meta.MetaModels.Completion;
import io.justinbeaver.meta.MetaModels.Container;
import io.justinbeaver.meta.MetaModels.DbInfo;
import io.justinbeaver.meta.MetaModels.SearchHit;
import io.justinbeaver.meta.MetaModels.TableDetails;
import io.justinbeaver.meta.MetaModels.TableInfo;
import io.justinbeaver.meta.MetadataService;
import io.justinbeaver.query.DataService;
import io.justinbeaver.query.QueryModels.DataPage;
import io.justinbeaver.query.QueryModels.QueryResult;
import io.justinbeaver.query.QueryModels.StatementResult;
import io.justinbeaver.query.QueryService;
import io.justinbeaver.session.DbSession;
import io.justinbeaver.session.SessionManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Everything you can do on an open connection: browse, query, page table data, control transactions. */
@RestController
@RequestMapping("/api/sessions/{connectionId}")
public class SessionController {

    private static final int MAX_ROWS_CAP = 100_000;
    /** "All rows" (maxRows = 0) still stops here, to protect the server's and the browser's memory. */
    private static final int ALL_ROWS_CAP = 1_000_000;

    /** @param maxRows 0 = all rows (up to {@value #ALL_ROWS_CAP}) */
    public record QueryRequest(@NotBlank String sql, @Min(0) @Max(MAX_ROWS_CAP) Integer maxRows,
                               @Min(1) @Max(3600) Integer timeoutSeconds, Boolean stopOnError) {
    }

    public record ToggleRequest(boolean value) {
    }

    private final SessionManager sessions;
    private final MetadataService metadata;
    private final QueryService queries;
    private final DataService data;
    private final EditService edits;
    private final DdlService ddl;
    private final ObjectService objects;

    public SessionController(SessionManager sessions, MetadataService metadata, QueryService queries,
                             DataService data, EditService edits, DdlService ddl, ObjectService objects) {
        this.ddl = ddl;
        this.objects = objects;
        this.sessions = sessions;
        this.metadata = metadata;
        this.queries = queries;
        this.data = data;
        this.edits = edits;
    }

    @GetMapping("/databases")
    public MetadataService.Databases databases(@PathVariable String connectionId) throws SQLException {
        DbSession session = sessions.require(connectionId);
        var saved = sessions.settingsOf(connectionId);
        return metadata.databases(session, saved != null && saved.showAllDatabases());
    }

    @GetMapping("/containers")
    public List<Container> containers(@PathVariable String connectionId) throws SQLException {
        return metadata.containers(sessions.require(connectionId));
    }

    @GetMapping("/tables")
    public List<TableInfo> tables(@PathVariable String connectionId, @RequestParam(required = false) String catalog,
                                  @RequestParam(required = false) String schema) throws SQLException {
        return metadata.tables(sessions.require(connectionId), catalog, schema);
    }

    @GetMapping("/table")
    public TableDetails table(@PathVariable String connectionId, @RequestParam(required = false) String catalog,
                              @RequestParam(required = false) String schema, @RequestParam String name)
            throws SQLException {
        return metadata.details(sessions.require(connectionId), catalog, schema, name);
    }

    @GetMapping("/data")
    public DataPage data(@PathVariable String connectionId, @RequestParam(required = false) String catalog,
                         @RequestParam(required = false) String schema, @RequestParam String table,
                         @RequestParam(defaultValue = "200") @Min(1) @Max(MAX_ROWS_CAP) int limit,
                         @RequestParam(defaultValue = "0") @Min(0) int offset,
                         @RequestParam(required = false) String orderBy,
                         @RequestParam(defaultValue = "false") boolean desc,
                         @RequestParam(required = false) String where) throws SQLException {
        return data.page(sessions.require(connectionId), catalog, schema, table, limit, offset, orderBy, desc, where, 60);
    }

    @PostMapping("/query")
    public QueryResult query(@PathVariable String connectionId, @Valid @RequestBody QueryRequest request)
            throws SQLException {
        return queries.execute(sessions.require(connectionId), request.sql(), maxRows(request), timeout(request));
    }

    @PostMapping("/script")
    public List<StatementResult> script(@PathVariable String connectionId, @Valid @RequestBody QueryRequest request)
            throws SQLException {
        return queries.executeScript(sessions.require(connectionId), request.sql(), maxRows(request), timeout(request),
                request.stopOnError() == null || request.stopOnError());
    }

    @GetMapping("/objects")
    public ObjectService.Objects objects(@PathVariable String connectionId, @RequestParam(required = false) String catalog,
                                         @RequestParam(required = false) String schema) throws SQLException {
        return objects.objects(sessions.require(connectionId), catalog, schema);
    }

    @GetMapping("/source")
    public Map<String, String> source(@PathVariable String connectionId, @RequestParam(required = false) String catalog,
                                      @RequestParam(required = false) String schema, @RequestParam ObjectService.Kind kind,
                                      @RequestParam String name) throws SQLException {
        return Map.of("source", objects.source(sessions.require(connectionId), catalog, schema, kind, name));
    }

    @GetMapping("/constraints")
    public List<ObjectService.ConstraintInfo> constraints(@PathVariable String connectionId,
                                                          @RequestParam(required = false) String catalog,
                                                          @RequestParam(required = false) String schema,
                                                          @RequestParam String name) throws SQLException {
        return objects.constraints(sessions.require(connectionId), catalog, schema, name);
    }

    @GetMapping("/roles")
    public List<ObjectService.RoleInfo> roles(@PathVariable String connectionId) throws SQLException {
        return objects.roles(sessions.require(connectionId));
    }

    @GetMapping("/privileges")
    public List<ObjectService.Privilege> privileges(@PathVariable String connectionId,
                                                    @RequestParam(required = false) String catalog,
                                                    @RequestParam(required = false) String schema,
                                                    @RequestParam String name) throws SQLException {
        return objects.privileges(sessions.require(connectionId), catalog, schema, name);
    }

    @GetMapping("/info")
    public DbInfo info(@PathVariable String connectionId) throws SQLException {
        return metadata.info(sessions.require(connectionId));
    }

    @GetMapping("/search")
    public List<SearchHit> search(@PathVariable String connectionId, @RequestParam String q,
                                  @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) throws SQLException {
        return metadata.search(sessions.require(connectionId), q, limit);
    }

    @PostMapping("/cell")
    public CellContent cell(@PathVariable String connectionId, @Valid @RequestBody CellRequest request) throws SQLException {
        return edits.readCell(sessions.require(connectionId), request);
    }

    @GetMapping("/ddl")
    public Map<String, String> ddl(@PathVariable String connectionId, @RequestParam(required = false) String catalog,
                                   @RequestParam(required = false) String schema, @RequestParam String name)
            throws SQLException {
        return Map.of("ddl", ddl.ddl(sessions.require(connectionId), catalog, schema, name));
    }

    @GetMapping("/count")
    public Map<String, Long> count(@PathVariable String connectionId, @RequestParam(required = false) String catalog,
                                   @RequestParam(required = false) String schema, @RequestParam String table,
                                   @RequestParam(required = false) String where) throws SQLException {
        return Map.of("count", data.count(sessions.require(connectionId), catalog, schema, table, where, 300));
    }

    @GetMapping("/completion")
    public Completion completion(@PathVariable String connectionId) throws SQLException {
        return metadata.completion(sessions.require(connectionId));
    }

    @PostMapping("/explain")
    public QueryResult explain(@PathVariable String connectionId, @Valid @RequestBody QueryRequest request)
            throws SQLException {
        return queries.explain(sessions.require(connectionId), request.sql(), maxRows(request), timeout(request));
    }

    @PostMapping("/edits/preview")
    public PreviewResult previewEdits(@PathVariable String connectionId, @Valid @RequestBody EditRequest request)
            throws SQLException {
        return edits.preview(sessions.require(connectionId), request);
    }

    @PostMapping("/edits/apply")
    public EditResult applyEdits(@PathVariable String connectionId, @Valid @RequestBody EditRequest request)
            throws SQLException {
        return edits.apply(sessions.require(connectionId), request);
    }

    @PostMapping("/cancel")
    public Map<String, Boolean> cancel(@PathVariable String connectionId) throws SQLException {
        return Map.of("cancelled", sessions.require(connectionId).cancelRunning());
    }

    @GetMapping("/transaction")
    public Map<String, Object> transaction(@PathVariable String connectionId) throws SQLException {
        DbSession session = sessions.require(connectionId);
        return session.call(conn -> Map.of("autoCommit", conn.getAutoCommit(),
                "isolation", isolationName(conn.getTransactionIsolation())));
    }

    public record IsolationRequest(@NotBlank String level) {
    }

    /** Transaction isolation of the session (DBeaver's "Transaction mode" menu). */
    @PostMapping("/isolation")
    public Map<String, Object> isolation(@PathVariable String connectionId, @Valid @RequestBody IsolationRequest request)
            throws SQLException {
        int level = switch (request.level()) {
            case "READ_UNCOMMITTED" -> java.sql.Connection.TRANSACTION_READ_UNCOMMITTED;
            case "READ_COMMITTED" -> java.sql.Connection.TRANSACTION_READ_COMMITTED;
            case "REPEATABLE_READ" -> java.sql.Connection.TRANSACTION_REPEATABLE_READ;
            case "SERIALIZABLE" -> java.sql.Connection.TRANSACTION_SERIALIZABLE;
            default -> throw new IllegalArgumentException("Unknown isolation level: " + request.level());
        };
        DbSession session = sessions.require(connectionId);
        return session.call(conn -> {
            conn.setTransactionIsolation(level);
            return Map.of("autoCommit", conn.getAutoCommit(), "isolation", isolationName(conn.getTransactionIsolation()));
        });
    }

    private static String isolationName(int level) {
        return switch (level) {
            case java.sql.Connection.TRANSACTION_READ_UNCOMMITTED -> "READ_UNCOMMITTED";
            case java.sql.Connection.TRANSACTION_READ_COMMITTED -> "READ_COMMITTED";
            case java.sql.Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE_READ";
            case java.sql.Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
            default -> "NONE";
        };
    }

    /** Default catalog/schema of the session: where unqualified names resolve. */
    public record SessionContext(String catalog, String schema) {
    }

    @GetMapping("/context")
    public SessionContext context(@PathVariable String connectionId) throws SQLException {
        return sessions.require(connectionId).call(SessionController::contextOf);
    }

    /**
     * DBeaver's "active schema" selector: PostgreSQL and others switch the schema (search_path),
     * MySQL/MariaDB switch the catalog (USE db). Unchanged parts are left alone.
     */
    @PostMapping("/context")
    public SessionContext setContext(@PathVariable String connectionId, @RequestBody SessionContext request)
            throws SQLException {
        return sessions.require(connectionId).call(conn -> {
            if (request.catalog() != null && !request.catalog().isBlank() && !request.catalog().equals(conn.getCatalog())) {
                conn.setCatalog(request.catalog());
            }
            if (request.schema() != null && !request.schema().isBlank()) {
                conn.setSchema(request.schema());
            }
            return contextOf(conn);
        });
    }

    private static SessionContext contextOf(java.sql.Connection conn) throws SQLException {
        String schema;
        try {
            schema = conn.getSchema();
        } catch (SQLException | AbstractMethodError | UnsupportedOperationException e) {
            schema = null; // drivers without schemas (MySQL) or an old JDBC level
        }
        return new SessionContext(conn.getCatalog(), schema);
    }

    @PostMapping("/autocommit")
    public Map<String, Boolean> autoCommit(@PathVariable String connectionId, @RequestBody ToggleRequest request)
            throws SQLException {
        DbSession session = sessions.require(connectionId);
        session.call(conn -> {
            conn.setAutoCommit(request.value());
            return null;
        });
        return Map.of("autoCommit", request.value());
    }

    @PostMapping("/commit")
    public Map<String, Boolean> commit(@PathVariable String connectionId) throws SQLException {
        sessions.require(connectionId).call(conn -> {
            conn.commit();
            return null;
        });
        return Map.of("ok", true);
    }

    @PostMapping("/rollback")
    public Map<String, Boolean> rollback(@PathVariable String connectionId) throws SQLException {
        sessions.require(connectionId).call(conn -> {
            conn.rollback();
            return null;
        });
        return Map.of("ok", true);
    }

    private static int maxRows(QueryRequest request) {
        if (request.maxRows() == null) {
            return 200;
        }
        return request.maxRows() == 0 ? ALL_ROWS_CAP : request.maxRows();
    }

    private static int timeout(QueryRequest request) {
        return request.timeoutSeconds() == null ? 60 : request.timeoutSeconds();
    }
}
