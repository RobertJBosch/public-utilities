/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.spellability.SpellAbility;

/**
 * Short, stable JSON for whatever a decision was about or chose: a card is "Name#id", a player its
 * name, a spell or ability "Host#id: description", a combat its attackers and blocks. Lists are cut
 * at {@link #MAX_ITEMS} with a count of the rest, so a "choose from your library" doesn't log 90
 * cards. Ids are per game (Forge numbers cards from 1 in every game), so they're stable on replay.
 */
final class Describe {
    static final int MAX_ITEMS = 12;
    private static final int MAX_TEXT = 120;

    private Describe() {}

    static JsonArray args(final Object[] args) {
        final JsonArray a = new JsonArray();
        for (final Object o : args) {
            a.add(of(o));
        }
        return a;
    }

    static JsonElement of(final Object o) {
        if (o == null) {
            return JsonNull.INSTANCE;
        }
        if (o instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (o instanceof Number n) {
            return new JsonPrimitive(n);
        }
        if (o instanceof CharSequence s) {
            return new JsonPrimitive(cut(s.toString()));
        }
        if (o instanceof Enum<?> e) {
            return new JsonPrimitive(e.name());
        }
        if (o instanceof Card c) {
            return new JsonPrimitive(card(c));
        }
        if (o instanceof GameEntity g) {   // players (and anything else that can be attacked or chosen)
            return new JsonPrimitive(g.getName());
        }
        if (o instanceof SpellAbility sa) {
            final Card host = sa.getHostCard();
            return new JsonPrimitive(cut((host == null ? "?" : card(host)) + ": " + sa));
        }
        if (o instanceof Combat c) {
            return combat(c);
        }
        if (o instanceof Pair<?, ?> p) {
            final JsonArray a = new JsonArray();
            a.add(of(p.getLeft()));
            a.add(of(p.getRight()));
            return a;
        }
        if (o instanceof Map<?, ?> m) {
            final JsonObject obj = new JsonObject();
            int n = 0;
            for (final Map.Entry<?, ?> e : m.entrySet()) {
                if (n++ == MAX_ITEMS) {
                    obj.addProperty("...", m.size() - MAX_ITEMS);
                    break;
                }
                final JsonElement key = of(e.getKey());
                obj.add(key.isJsonPrimitive() ? key.getAsString() : key.toString(), of(e.getValue()));
            }
            return obj;
        }
        if (o instanceof Iterable<?> it) {
            final JsonArray a = new JsonArray();
            int n = 0;
            int more = 0;
            for (final Object x : it) {
                if (n < MAX_ITEMS) {
                    a.add(of(x));
                } else {
                    more++;
                }
                n++;
            }
            if (more > 0) {
                a.add("+" + more + " more");
            }
            return a;
        }
        if (o.getClass().isArray() && o instanceof Object[] arr) {
            return of(java.util.Arrays.asList(arr));
        }
        return new JsonPrimitive(o.getClass().getSimpleName());
    }

    private static String card(final Card c) {
        return c.getName() + "#" + c.getId();
    }

    private static JsonObject combat(final Combat c) {
        final JsonObject o = new JsonObject();
        final JsonArray attacks = new JsonArray();
        for (final Card a : c.getAttackers()) {
            final GameEntity d = c.getDefenderByAttacker(a);
            attacks.add(card(a) + " -> " + (d == null ? "?" : d instanceof Card dc ? card(dc) : d.getName()));
        }
        final JsonArray blocks = new JsonArray();
        for (final Card b : c.getAllBlockers()) {
            for (final Card a : c.getAttackersBlockedBy(b)) {
                blocks.add(card(b) + " blocks " + card(a));
            }
        }
        o.add("attacks", attacks);
        o.add("blocks", blocks);
        return o;
    }

    private static String cut(final String s) {
        final String one = s.replace('\n', ' ');
        return one.length() <= MAX_TEXT ? one : one.substring(0, MAX_TEXT - 3) + "...";
    }
}
