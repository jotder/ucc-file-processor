package com.gamma.intelligence.context;

import com.gamma.service.CollectorService;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.signal.Signals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The situation-frame composer (S5, embedded-intelligence-plan §2): a pure, deterministic function
 * from the current ledger + session inputs to a bounded text frame the model can ground on —
 * <em>identity</em> (role), <em>focus</em> (page context), a <em>live Signal overlay</em> (the most
 * recent WARN+ signals, newest first), and a <em>knowledge</em> pointer to the retrieval/tool seams
 * (the static RAG corpus and tool belt already cover knowledge; this frame only points at them).
 *
 * <p>Deterministic by construction: same ledger contents + same inputs → byte-identical frame
 * (overlay ties broken by {@code signalId}, focus maps rendered sorted). Budgeted: the frame never
 * exceeds {@link #FRAME_BUDGET_CHARS}; overlay lines are evicted oldest-first to fit.
 */
public final class ContextBroker {

    /** Max recent WARN+ signals in the live overlay. */
    public static final int OVERLAY_LIMIT = 8;
    /** Hard character budget for the whole frame; overlay lines are evicted oldest-first to fit. */
    public static final int FRAME_BUDGET_CHARS = 3000;

    private static final Severity OVERLAY_FLOOR = Severity.WARN;

    private final CollectorService service;

    public ContextBroker(CollectorService service) {
        this.service = service;
    }

    /**
     * The full situation frame for one session: identity + focus + live overlay + knowledge pointer.
     * {@code role} and {@code page} (the SPI's {@code pageId}/{@code entityIds}/{@code filters} map)
     * may be null/empty — the sections render as {@code none} rather than vanishing, so the frame
     * shape is stable.
     */
    public String frame(String role, Map<String, Object> page) {
        List<String> overlay = overlayLines();
        while (!overlay.isEmpty() && render(role, page, overlay).length() > FRAME_BUDGET_CHARS) {
            overlay.remove(overlay.size() - 1); // evict the oldest overlay line (list is newest first)
        }
        String frame = render(role, page, overlay);
        return frame.length() <= FRAME_BUDGET_CHARS ? frame : frame.substring(0, FRAME_BUDGET_CHARS);
    }

    /** Just the live Signal overlay section — appended to the per-ask system prompt (see
     *  {@code InspectoPromptProfile}), which is the only pack seam that reaches the model per ask. */
    public String liveOverlay() {
        return overlaySection(overlayLines());
    }

    private String render(String role, Map<String, Object> page, List<String> overlay) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("[SITUATION]\n");
        sb.append("identity: role=").append(role == null || role.isBlank() ? "unknown" : role.trim()).append('\n');
        sb.append("focus: ").append(focusLine(page)).append('\n');
        sb.append(overlaySection(overlay));
        sb.append("knowledge: use glossary_lookup/docs_search for definitions and docs; ")
          .append("signals_query/signal_timeline for operational history and failure causes.");
        return sb.toString();
    }

    private static String overlaySection(List<String> overlay) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("signals (recent ").append(OVERLAY_FLOOR.name()).append("+, newest first):\n");
        if (overlay.isEmpty()) {
            sb.append("- none\n");
        } else {
            for (String line : overlay) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** The overlay as rendered lines, newest first, ties broken by signalId for a stable order. */
    private List<String> overlayLines() {
        List<Signal> recent = new ArrayList<>(
                Signals.query(service.events(), null, null, null, OVERLAY_FLOOR, null, OVERLAY_LIMIT));
        recent.sort(Comparator.comparing(Signal::at)
                .thenComparing(Signal::signalId, Comparator.nullsFirst(Comparator.naturalOrder()))
                .reversed());
        List<String> lines = new ArrayList<>(recent.size());
        for (Signal s : recent) {
            lines.add("- " + s.at() + " " + s.severity().name() + " " + s.type()
                    + " [" + s.signalId() + "] " + s.message());
        }
        return lines;
    }

    /** Deterministic focus rendering: pageId plus sorted entityIds/filters, or {@code none}. */
    private static String focusLine(Map<String, Object> page) {
        if (page == null || page.isEmpty()) return "none";
        Object pageId = page.get("pageId");
        StringBuilder sb = new StringBuilder();
        sb.append("pageId=").append(pageId == null ? "unknown" : pageId);
        appendSorted(sb, " entityIds", page.get("entityIds"));
        appendSorted(sb, " filters", page.get("filters"));
        return sb.toString();
    }

    private static void appendSorted(StringBuilder sb, String label, Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) return;
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                sorted.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        sb.append(label).append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) sb.append(' ');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        sb.append('}');
    }
}
