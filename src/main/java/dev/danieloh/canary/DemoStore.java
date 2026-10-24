package dev.danieloh.canary;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static dev.danieloh.canary.Domain.*;

@ApplicationScoped
public class DemoStore {
    public final Map<String, Evaluation> evaluations = new ConcurrentHashMap<>();
    private final Deque<Event> events = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();
    public volatile String latestId;
    public synchronized void event(String actor, String message) {
        events.addLast(new Event(sequence.incrementAndGet(), Instant.now(), actor, message));
        while (events.size() > 300) events.removeFirst();
        org.jboss.logging.Logger.getLogger("canary." + actor).info(message);
    }
    public synchronized List<Event> events() { return List.copyOf(events); }
    public Evaluation require(String id) {
        var evaluation = evaluations.get(id);
        if (evaluation == null) throw new jakarta.ws.rs.NotFoundException("Unknown evaluation");
        return evaluation;
    }
    public void add(Evaluation e) {
        if (evaluations.size() >= 50) evaluations.values().stream().filter(x -> !x.status.equals("RUNNING"))
                .min(Comparator.comparing(x -> x.startedAt)).ifPresent(x -> evaluations.remove(x.id));
        evaluations.put(e.id,e); latestId=e.id;
    }
}
