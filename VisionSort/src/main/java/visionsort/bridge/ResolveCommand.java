/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks LLC
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import forge.StaticData;
import forge.card.CardDb;

/**
 * The name Forge knows each card by, so VisionSort can write decklists Forge accepts:
 *
 *   {"cmd":"resolve", "names":["Revitalizing Repast // Old-Growth Grove", "Fire // Ice", ...]}
 *   -> {"type":"resolved", "names":{"Revitalizing Repast // Old-Growth Grove":"Revitalizing Repast",
 *                                    "Fire // Ice":"Fire // Ice", "Not A Card":null}}
 *
 * Forge loads a decklist line by name and silently drops a card it can't find. Its names differ
 * from Scryfall's for some multi-faced cards: a split card keeps "Fire // Ice", but a modal
 * double-faced, transforming, adventure or flip card is known by its front face alone. Rather than
 * guess from card types, VisionSort asks Forge: the name as given, then the front face, else null
 * (a card this Forge version doesn't have - newer than the release, or digital-only).
 */
final class ResolveCommand {
    private ResolveCommand() {}

    static JsonObject run(final JsonObject req, final JsonObject reply) {
        if (!req.has("names") || !req.get("names").isJsonArray()) {
            throw new IllegalArgumentException("resolve needs \"names\": a list of card names");
        }
        final CardDb db = StaticData.instance().getCommonCards();
        final JsonObject out = new JsonObject();
        for (final JsonElement e : req.getAsJsonArray("names")) {
            final String name = e.getAsString();
            final String forge = resolve(db, name);
            out.add(name, forge == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(forge));
        }
        reply.add("names", out);
        return reply;
    }

    static String resolve(final CardDb db, final String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (db.contains(name)) {
            return db.getCard(name).getName();
        }
        final int split = name.indexOf(" // ");
        if (split > 0) {
            final String front = name.substring(0, split);
            if (db.contains(front)) {
                return db.getCard(front).getName();
            }
        }
        return null;
    }
}
