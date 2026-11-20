/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import com.google.common.collect.Multiset;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.mana.ManaPool;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * VisionSort's own model of a game's state, as JSON, and its checksum.
 *
 * It holds what a player (or VisionSort's table) needs to see and what a restore needs to rebuild:
 * per player life, poison, counters, mana pool, lands played and cards drawn this turn, commander
 * damage taken and commander tax, city's blessing, speed, ring temptations, and every zone in
 * order (the library too: hidden from players, but it decides what happens next, so it belongs in
 * the checksum - VisionSort hides it). Permanents add tapped / counters / damage / attachment /
 * face-down / transformed / summoning sick / phased out, and power and toughness for creatures.
 * Then the stack, turn, phase, whose turn and priority it is, monarch, initiative, day / night,
 * the current combat, and Forge's game timestamp (see of()).
 *
 * A checksum can only catch drift in what it covers; the timestamp is there so most hidden
 * changes (effects that don't show in this model) still change it.
 *
 * It's built in a fixed order, so the same state always gives the same JSON and the same checksum
 * (SHA-256 of the compact JSON, first 16 hex digits). Two games whose checksums match at every
 * step played out identically, as far as anything a player could see or a restore would need.
 */
final class StateSnapshot {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final ZoneType[] ZONES = {ZoneType.Command, ZoneType.Hand, ZoneType.Library,
            ZoneType.Graveyard, ZoneType.Exile, ZoneType.Battlefield};
    private static final String[] COLORS = {"W", "U", "B", "R", "G", "C"};
    private static final byte[] COLOR_BYTES = {MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK,
            MagicColor.RED, MagicColor.GREEN, MagicColor.COLORLESS};

    private StateSnapshot() {}

    static JsonObject of(final Game game) {
        final JsonObject s = new JsonObject();
        s.addProperty("turn", game.getPhaseHandler().getTurn());
        s.addProperty("phase", String.valueOf(game.getPhaseHandler().getPhase()));
        final Player active = game.getPhaseHandler().getPlayerTurn();
        s.addProperty("active", active == null ? null : active.getName());
        final Player priority = game.getPhaseHandler().getPriorityPlayer();
        s.addProperty("priority", priority == null ? null : priority.getName());
        // Forge's timestamp counter ticks with every timestamped effect, so it catches most changes
        // the rest of this model doesn't show ("until end of turn" effects, static abilities applied)
        s.addProperty("timestamp", game.getTimestamp());
        s.addProperty("monarch", game.getMonarch() == null ? null : game.getMonarch().getName());
        s.addProperty("initiative", game.getHasInitiative() == null ? null : game.getHasInitiative().getName());
        final Boolean day = game.getDayTime();
        s.addProperty("day", day == null ? null : day ? "day" : "night");
        s.add("combat", game.getCombat() == null ? com.google.gson.JsonNull.INSTANCE : Describe.of(game.getCombat()));

        final JsonArray stack = new JsonArray();
        for (final SpellAbilityStackInstance si : game.getStack()) {
            final JsonObject item = new JsonObject();
            item.addProperty("source", si.getSourceCard() == null ? "?" : card(si.getSourceCard()));
            item.addProperty("text", si.getStackDescription());
            stack.add(item);
        }
        s.add("stack", stack);

        final JsonArray players = new JsonArray();
        for (final Player p : game.getRegisteredPlayers()) {
            players.add(player(p));
        }
        s.add("players", players);
        return s;
    }

    /** First 16 hex digits of the SHA-256 of the state's compact JSON. */
    static String checksum(final JsonObject state) {
        try {
            final byte[] d = MessageDigest.getInstance("SHA-256").digest(GSON.toJson(state).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every JVM has SHA-256
        }
    }

    private static JsonObject player(final Player p) {
        final JsonObject o = new JsonObject();
        o.addProperty("name", p.getName());
        o.addProperty("life", p.getLife());
        o.addProperty("poison", p.getPoisonCounters());
        o.addProperty("lost", p.hasLost());
        o.addProperty("lands_played", p.getLandsPlayedThisTurn());
        o.addProperty("drawn_this_turn", p.getNumDrawnThisTurn());
        o.addProperty("blessing", p.hasBlessing());
        o.addProperty("speed", p.getSpeed());
        o.addProperty("ring_tempted", p.getNumRingTemptedYou());
        o.add("counters", counters(p));

        final ManaPool pool = p.getManaPool();
        final JsonObject mana = new JsonObject();
        for (int i = 0; i < COLORS.length; i++) {
            final int n = pool.getAmountOfColor(COLOR_BYTES[i]);
            if (n > 0) {
                mana.addProperty(COLORS[i], n);
            }
        }
        o.add("mana", mana);

        final JsonObject cmdDamage = new JsonObject();   // commander damage this player has taken
        for (final Map.Entry<Card, Integer> e : p.getCommanderDamage()) {
            if (e.getValue() > 0) {
                cmdDamage.addProperty(card(e.getKey()), e.getValue());
            }
        }
        o.add("commander_damage", cmdDamage);
        final JsonObject tax = new JsonObject();          // times each own commander was cast from the command zone
        for (final Card c : p.getCommanders()) {
            tax.addProperty(card(c), p.getCommanderCast(c));
        }
        o.add("commander_cast", tax);

        final JsonObject zones = new JsonObject();
        for (final ZoneType z : ZONES) {
            final JsonArray cards = new JsonArray();
            for (final Card c : p.getCardsIn(z)) {
                cards.add(z == ZoneType.Battlefield ? permanent(c) : new com.google.gson.JsonPrimitive(card(c)));
            }
            zones.add(z.name(), cards);
        }
        o.add("zones", zones);
        return o;
    }

    private static JsonObject permanent(final Card c) {
        final JsonObject o = new JsonObject();
        o.addProperty("card", card(c));
        o.addProperty("controller", c.getController() == null ? null : c.getController().getName());
        o.addProperty("tapped", c.isTapped());
        if (c.isCreature()) {
            final JsonArray pt = new JsonArray();
            pt.add(c.getNetPower());
            pt.add(c.getNetToughness());
            o.add("pt", pt);
            o.addProperty("sick", c.isSick());
            o.addProperty("damage", c.getDamage());
        }
        final JsonObject counters = counters(c);
        if (counters.size() > 0) {
            o.add("counters", counters);
        }
        final GameEntity attached = c.getEntityAttachedTo();
        if (attached != null) {
            o.addProperty("attached_to", attached instanceof Card a ? card(a) : attached.getName());
        }
        if (c.isFaceDown()) {
            o.addProperty("face_down", true);
        }
        if (c.isTransformed()) {
            o.addProperty("transformed", true);
        }
        if (c.isPhasedOut()) {
            o.addProperty("phased_out", true);
        }
        return o;
    }

    private static JsonObject counters(final GameEntity e) {
        final JsonObject o = new JsonObject();
        final Multiset<CounterType> counters = e.getCounters();
        // sorted by name, so the JSON (and the checksum) doesn't depend on the multiset's order
        counters.entrySet().stream()
                .sorted(java.util.Comparator.comparing(x -> x.getElement().getName()))
                .forEach(x -> o.addProperty(x.getElement().getName(), x.getCount()));
        return o;
    }

    static String card(final Card c) {
        return c.getName() + "#" + c.getId();
    }
}
