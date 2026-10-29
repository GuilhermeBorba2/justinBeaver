package io.justinbeaver.edit;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** Wire shapes for editing table rows. */
public final class EditModels {

    private EditModels() {
    }

    public enum Op { INSERT, UPDATE, DELETE }

    /**
     * One row change. {@code key} holds the original primary-key values (update/delete);
     * {@code values} the new column values (insert/update). Maps may contain JSON nulls.
     */
    public record RowChange(@NotNull Op op, Map<String, Object> key, Map<String, Object> values) {
    }

    public record EditRequest(String catalog, String schema, @NotBlank String table,
                              @NotEmpty List<@Valid @NotNull RowChange> changes) {
    }

    /** Address of one cell by primary key, for the value viewer. */
    public record CellRequest(String catalog, String schema, @NotBlank String table, @NotBlank String column,
                              @NotNull Map<String, Object> key) {
    }

    /**
     * Full content of a cell: {@code text} for character data, {@code base64} for binary data.
     *
     * @param mime     guessed from magic bytes for binary data (image/png, application/pdf...), else null
     * @param truncated the value was larger than the viewer limit
     */
    public record CellContent(String text, String base64, long size, String mime, boolean truncated) {
    }

    /** SQL shown to the user before applying; literals are rendered for reading only, execution binds parameters. */
    public record PreviewResult(List<String> statements) {
    }

    /**
     * @param committed true when the save was committed; false when it joined the session's open
     *                  manual transaction and still needs Commit or Rollback
     */
    public record EditResult(int statements, long rowsAffected, boolean committed) {
    }
}
