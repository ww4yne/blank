package app.moting;

import java.util.ArrayDeque;

final class PlaybackTrace {
    enum Event { PREPARE, APPEND, RESUME, PAUSE, NEXT, PREVIOUS, SKIP, STATE, BUFFER, ITEM, QUEUE, ERROR, TIMEOUT, STOP, REBUILD }
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final long started;

    PlaybackTrace(long started) { this.started = started; }

    String add(Event event, long now, int state, boolean buffering, long position, long buffered,
               int count, boolean current, int type, int code, int component, int cause) {
        if (events.size() == 24) events.removeFirst();
        events.addLast("+" + Math.max(0, now - started) / 1000 + "s " + event.name()
                + " state=" + state + " buffer=" + buffering
                + " pos=" + position + " loaded=" + buffered
                + " queue=" + count + " item=" + current
                + (event == Event.ERROR ? " error=" + type + "/" + code + "/" + component + "/" + cause : ""));
        return String.join("\n", events);
    }
}
