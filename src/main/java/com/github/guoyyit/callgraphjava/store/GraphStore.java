package com.github.guoyyit.callgraphjava.store;

import com.github.guoyyit.callgraphjava.model.GraphSnapshot;

import java.util.concurrent.atomic.AtomicReference;

public class GraphStore {
    private final AtomicReference<GraphSnapshot> current = new AtomicReference<>(GraphSnapshot.empty());

    public GraphSnapshot current() {
        return current.get();
    }

    public void commit(GraphSnapshot snapshot) {
        current.set(snapshot);
    }

    public boolean commitIfNewer(GraphSnapshot snapshot) {
        while (true) {
            GraphSnapshot existing = current.get();
            if (snapshot.revision() <= existing.revision()) {
                return false;
            }
            if (current.compareAndSet(existing, snapshot)) {
                return true;
            }
        }
    }
}
