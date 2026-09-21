package com.xander.lab.core.stream;

import com.xander.lab.common.security.ConnectionRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for only the Core-hosted legacy Agent/content streams being drained.
 *
 * <p>It deliberately stays separate from the security connection registry: Studio SSE still
 * belongs to Core during Wave 2 and must not be force-closed by an Agent/content drain.</p>
 */
@Component
public class CoreLegacyStreamDrainRegistry {

    private final Set<ConnectionRegistry.ConnectionHandle> handles = ConcurrentHashMap.newKeySet();

    public void register(ConnectionRegistry.ConnectionHandle handle) {
        if (handle != null) handles.add(handle);
    }

    public void unregister(ConnectionRegistry.ConnectionHandle handle) {
        if (handle != null) handles.remove(handle);
    }

    /** Sends the migration reason to every still-connected legacy stream. */
    public int drainAll() {
        int closed = 0;
        for (ConnectionRegistry.ConnectionHandle handle : handles) {
            try {
                handle.close(ConnectionRegistry.REASON_STREAM_MIGRATION);
                closed++;
            } catch (RuntimeException ignored) {
                // A disconnected client is already on the way out; the next callback removes it.
            }
        }
        return closed;
    }

    public int activeCount() {
        return handles.size();
    }
}
