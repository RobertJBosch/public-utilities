/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks LLC
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonObject;

/** Per seat: how many decisions of each kind it made, and how many its policy changed. Always kept. */
final class DecisionCounts {
    private final Map<String, Map<String, int[]>> bySeat = new TreeMap<>();

    void count(final String seat, final String decision, final boolean changed) {
        final int[] c = bySeat.computeIfAbsent(seat, s -> new TreeMap<>()).computeIfAbsent(decision, d -> new int[2]);
        c[0]++;
        if (changed) {
            c[1]++;
        }
    }

    /** {"seat": {"decision": [made, changed], ...}, ...} */
    JsonObject toJson() {
        final JsonObject out = new JsonObject();
        bySeat.forEach((seat, decisions) -> {
            final JsonObject o = new JsonObject();
            decisions.forEach((d, c) -> {
                final com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                a.add(c[0]);
                a.add(c[1]);
                o.add(d, a);
            });
            out.add(seat, o);
        });
        return out;
    }
}
