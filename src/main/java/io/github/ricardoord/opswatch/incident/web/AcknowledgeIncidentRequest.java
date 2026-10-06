package io.github.ricardoord.opswatch.incident.web;

import io.github.ricardoord.opswatch.shared.text.VisibleText;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/incidents/{incidentId}/acknowledge}; the body itself is optional.
 *
 * @param note one line for whoever reads the timeline. The API returns it as written: whoever shows it in a page
 *     escapes it
 */
public record AcknowledgeIncidentRequest(
        @Size(max = NOTE_MAX_LENGTH) @Pattern(regexp = VisibleText.PATTERN, message = VisibleText.MESSAGE) @Nullable
        String note) {

    /** The limit of {@code incident_timeline.note}. */
    public static final int NOTE_MAX_LENGTH = 500;

    /** Surrounding spaces are a typing slip; a blank note is none. */
    public AcknowledgeIncidentRequest {
        note = note == null || note.isBlank() ? null : note.strip();
    }
}
