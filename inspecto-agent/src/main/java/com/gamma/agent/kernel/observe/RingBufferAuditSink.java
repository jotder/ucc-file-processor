package com.gamma.agent.kernel.observe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The default {@link AuditSink}: a bounded in-memory ring buffer of the most recent events (UCC's
 * behavior). Thread-safe; oldest events are dropped once {@code capacity} is exceeded.
 */
public final class RingBufferAuditSink implements AuditSink {

    private final int capacity;
    private final Deque<AgentEvent> buffer;

    public RingBufferAuditSink(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    @Override
    public synchronized void emit(AgentEvent event) {
        if (event == null) return;
        if (buffer.size() == capacity) buffer.removeFirst();
        buffer.addLast(event);
    }

    /** Up to {@code n} most-recent events, newest first. */
    public synchronized List<AgentEvent> recent(int n) {
        List<AgentEvent> out = new ArrayList<>(Math.min(Math.max(0, n), buffer.size()));
        var it = buffer.descendingIterator();
        while (it.hasNext() && out.size() < n) out.add(it.next());
        return out;
    }

    /** The number of buffered events. */
    public synchronized int size() {
        return buffer.size();
    }
}
