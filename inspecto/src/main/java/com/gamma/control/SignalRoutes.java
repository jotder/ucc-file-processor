package com.gamma.control;

import com.gamma.signal.Signal;
import com.gamma.signal.Signals;
import com.sun.net.httpserver.HttpExchange;

import java.util.List;
import java.util.Map;

/**
 * The signal-ledger read view ({@code GET /signals}, job-framework §8.1 / §14). Signals persist as
 * {@code EventType.SIGNAL} events on the one ledger (§19.2); this route reconstructs them and filters
 * by {@code type} (exact or {@code prefix.*} glob), {@code since} (epoch millis) and {@code correlationId}
 * — the last two applied in-store, the type glob in Java. Space-scoped like {@code /events} (the
 * {@code /spaces/{id}} prefix routes to the space's event store).
 */
final class SignalRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/signals", (e, m) -> signals(api, e));
    }

    /** {@code GET /signals?type=&since=&correlationId=&limit=} — the correlation-chain-filterable ledger view. */
    private Object signals(ApiContext api, HttpExchange e) {
        int limit = ApiContext.parseIntOr(ApiContext.query(e, "limit"), 200);
        Long since = parseLong(ApiContext.query(e, "since"));
        List<Signal> found = Signals.query(api.service().events(),
                ApiContext.query(e, "type"), since, ApiContext.query(e, "correlationId"), limit);
        return found.stream().map(Signal::toMap).toList();
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            throw new ApiException(400, "invalid 'since' (expected epoch millis): " + s);
        }
    }
}
