/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.common.collect.Multiset;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import forge.card.CardStateName;
import forge.card.CardTypeView;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.keyword.Keyword;
import forge.game.mana.ManaPool;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;

/**
 * The game as one player may see it, for VisionSort's play table (the "state" of the play
 * protocol: visionsort/battle/play.py in VisionSort documents every field).
 *
 * Unlike StateSnapshot (the checksum model, which sees everything), this hides what the viewer
 * may not see: the other players' hands (unless the practice option shows them), every library
 * (only counts) and face-down cards the viewer doesn't control. Cards are identified by Forge's
 * card id, which stays with a card as it moves between zones; seats are positions in the game's
 * registered players.
 */
final class ViewState {
    private static final ZoneType[] ZONES = {ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard,
            ZoneType.Exile, ZoneType.Command};
    private static final String[] COLORS = {"W", "U", "B", "R", "G", "C"};
    private static final byte[] COLOR_BYTES = {MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK,
            MagicColor.RED, MagicColor.GREEN, MagicColor.COLORLESS};
    /** The keywords the table marks on a card (Forge has ~200; these change how you play against it). */
    private static final Keyword[] SHOWN_KEYWORDS = {Keyword.FLYING, Keyword.REACH, Keyword.TRAMPLE,
            Keyword.VIGILANCE, Keyword.HASTE, Keyword.DEATHTOUCH, Keyword.LIFELINK, Keyword.FIRST_STRIKE,
            Keyword.DOUBLE_STRIKE, Keyword.MENACE, Keyword.HEXPROOF, Keyword.INDESTRUCTIBLE, Keyword.DEFENDER,
            Keyword.FLASH, Keyword.WARD, Keyword.SHROUD};

    private ViewState() {}

    static JsonObject of(final Game game, final Player viewer, final boolean showHands, final int step) {
        final List<Player> seats = game.getRegisteredPlayers();
        final JsonObject s = new JsonObject();
        s.addProperty("step", step);
        s.addProperty("turn", game.getPhaseHandler().getTurn());
        s.addProperty("phase", phase(game.getPhaseHandler().getPhase()));
        s.add("active", seat(seats, game.getPhaseHandler().getPlayerTurn()));
        s.add("priority", seat(seats, game.getPhaseHandler().getPriorityPlayer()));
        s.add("monarch", seat(seats, game.getMonarch()));

        final JsonArray players = new JsonArray();
        final JsonArray cards = new JsonArray();
        final JsonObject zones = new JsonObject();
        for (final Player p : seats) {
            players.add(player(seats, p, viewer));
            final JsonObject own = new JsonObject();
            for (final ZoneType z : ZONES) {
                final JsonArray ids = new JsonArray();
                for (final Card c : p.getCardsIn(z)) {
                    ids.add(c.getId());
                    cards.add(card(seats, c, zone(z), viewer, showHands));
                }
                own.add(zone(z), ids);
            }
            zones.add(String.valueOf(seats.indexOf(p)), own);
        }
        final JsonArray stack = new JsonArray();
        for (final SpellAbilityStackInstance si : game.getStack()) {       // top first
            final JsonObject item = new JsonObject();
            item.addProperty("id", si.getId());
            final Card source = si.getSourceCard();
            item.add("card", source == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(source.getId()));
            item.add("controller", seat(seats, si.getActivatingPlayer()));
            item.addProperty("text", si.getStackDescription());
            item.add("targets", targets(seats, si.getSpellAbility()));
            stack.add(item);
            if (source != null && source.isInZone(ZoneType.Stack)) {
                cards.add(card(seats, source, "stack", viewer, showHands));
            }
        }
        s.add("players", players);
        s.add("cards", cards);
        s.add("zones", zones);
        s.add("stack", stack);
        s.add("combat", combat(seats, game.getCombat()));
        return s;
    }

    static int seatOf(final List<Player> seats, final Player p) {
        for (int i = 0; i < seats.size(); i++) {
            if (seats.get(i).getId() == p.getId()) {
                return i;
            }
        }
        return -1;
    }

    private static com.google.gson.JsonElement seat(final List<Player> seats, final Player p) {
        return p == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(seatOf(seats, p));
    }

    static JsonObject ref(final List<Player> seats, final Object entity) {
        final JsonObject r = new JsonObject();
        if (entity instanceof Player p) {
            r.addProperty("player", seatOf(seats, p));
        } else if (entity instanceof Card c) {
            r.addProperty("card", c.getId());
        }
        return r;
    }

    private static JsonObject player(final List<Player> seats, final Player p, final Player viewer) {
        final JsonObject o = new JsonObject();
        o.addProperty("seat", seatOf(seats, p));
        o.addProperty("name", p.getName());
        o.addProperty("human", p.getId() == viewer.getId());
        o.addProperty("life", p.getLife());
        o.addProperty("poison", p.getPoisonCounters());
        o.addProperty("library", p.getCardsIn(ZoneType.Library).size());
        o.addProperty("hand", p.getCardsIn(ZoneType.Hand).size());
        final ManaPool pool = p.getManaPool();
        final JsonObject mana = new JsonObject();
        for (int i = 0; i < COLORS.length; i++) {
            final int n = pool.getAmountOfColor(COLOR_BYTES[i]);
            if (n > 0) {
                mana.addProperty(COLORS[i], n);
            }
        }
        o.add("mana", mana);
        final JsonObject cmd = new JsonObject();
        for (final Map.Entry<Card, Integer> e : p.getCommanderDamage()) {
            if (e.getValue() > 0) {
                cmd.addProperty(String.valueOf(e.getKey().getId()), e.getValue());
            }
        }
        o.add("commander_damage", cmd);
        o.addProperty("lost", p.hasLost());
        o.addProperty("lands_played", p.getLandsPlayedThisTurn());
        return o;
    }

    static JsonObject card(final List<Player> seats, final Card c, final String zone, final Player viewer,
            final boolean showHands) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", c.getId());
        o.addProperty("owner", seatOf(seats, c.getOwner()));
        o.addProperty("zone", zone);
        final boolean yours = c.getController() != null && c.getController().getId() == viewer.getId();
        final boolean hiddenHand = zone.equals("hand") && c.getOwner().getId() != viewer.getId() && !showHands;
        if (hiddenHand || (c.isFaceDown() && !yours)) {
            o.addProperty("hidden", true);
            if (c.isFaceDown()) {
                o.addProperty("face_down", true);
                o.addProperty("tapped", c.isTapped());
                o.add("controller", seat(seats, c.getController()));
            }
            return o;
        }
        o.addProperty("name", c.isFaceDown() ? c.getState(CardStateName.Original).getName() : c.getName());
        final IPaperCard paper = c.getPaperCard();
        o.addProperty("set", paper == null || paper.getEdition() == null ? "" : paper.getEdition());
        o.addProperty("number", paper == null || paper.getCollectorNumber() == null ? "" : paper.getCollectorNumber());
        o.add("controller", seat(seats, c.getController()));
        o.addProperty("tapped", c.isTapped());
        o.add("types", types(c.getType()));
        o.addProperty("cost", c.getManaCost() == null || c.getManaCost().isNoCost() ? "" : c.getManaCost().toString());
        o.addProperty("text", c.getOracleText() == null ? "" : c.getOracleText());
        if (c.isCreature()) {
            o.addProperty("power", c.getNetPower());
            o.addProperty("toughness", c.getNetToughness());
            o.addProperty("sick", c.isSick());
        }
        o.addProperty("damage", c.getDamage());
        o.addProperty("token", c.isToken());
        o.addProperty("face_down", c.isFaceDown());
        o.addProperty("commander", c.isCommander());
        final JsonObject counters = new JsonObject();
        final Multiset<CounterType> all = c.getCounters();
        all.entrySet().stream().sorted(java.util.Comparator.comparing(x -> x.getElement().getName()))
                .forEach(x -> counters.addProperty(x.getElement().getName(), x.getCount()));
        o.add("counters", counters);
        final GameEntity attached = c.getEntityAttachedTo();
        if (attached instanceof Card a) {
            o.addProperty("attached_to", a.getId());
        } else {
            o.add("attached_to", JsonNull.INSTANCE);
        }
        final JsonArray keywords = new JsonArray();
        for (final Keyword k : SHOWN_KEYWORDS) {
            if (c.hasKeyword(k)) {
                keywords.add(k.toString());
            }
        }
        o.add("keywords", keywords);
        return o;
    }

    private static JsonArray types(final CardTypeView type) {
        final JsonArray a = new JsonArray();
        type.getSupertypes().forEach(t -> a.add(t.toString()));
        type.getCoreTypes().forEach(t -> a.add(t.toString()));
        type.getSubtypes().forEach(a::add);
        return a;
    }

    private static JsonArray targets(final List<Player> seats, final SpellAbility sa) {
        final JsonArray a = new JsonArray();
        for (SpellAbility part = sa; part != null; part = part.getSubAbility()) {
            final TargetChoices t = part.getTargets();
            if (t == null) {
                continue;
            }
            t.getTargetCards().forEach(c -> a.add(ref(seats, c)));
            t.getTargetPlayers().forEach(p -> a.add(ref(seats, p)));
        }
        return a;
    }

    private static JsonArray combat(final List<Player> seats, final Combat combat) {
        final JsonArray a = new JsonArray();
        if (combat == null) {
            return a;
        }
        for (final Card attacker : combat.getAttackers()) {
            final JsonObject at = new JsonObject();
            at.addProperty("attacker", attacker.getId());
            at.add("defender", ref(seats, combat.getDefenderByAttacker(attacker)));
            final JsonArray blockers = new JsonArray();
            combat.getBlockers(attacker).forEach(b -> blockers.add(b.getId()));
            at.add("blockers", blockers);
            a.add(at);
        }
        return a;
    }

    static String zone(final ZoneType z) {
        return z.name().toLowerCase(Locale.ROOT);
    }

    /** Forge's phases with the protocol's names (play.PHASES). */
    static String phase(final PhaseType p) {
        if (p == null) {
            return "untap";
        }
        return switch (p) {
            case UNTAP -> "untap";
            case UPKEEP -> "upkeep";
            case DRAW -> "draw";
            case MAIN1 -> "main1";
            case COMBAT_BEGIN -> "combat_begin";
            case COMBAT_DECLARE_ATTACKERS -> "attackers";
            case COMBAT_DECLARE_BLOCKERS -> "blockers";
            case COMBAT_FIRST_STRIKE_DAMAGE -> "first_strike_damage";
            case COMBAT_DAMAGE -> "damage";
            case COMBAT_END -> "combat_end";
            case MAIN2 -> "main2";
            case END_OF_TURN -> "end";
            case CLEANUP -> "cleanup";
            default -> p.name().toLowerCase(Locale.ROOT);
        };
    }

    /** The protocol's phase name back to Forge's (for stops). */
    static PhaseType phaseType(final String name) {
        for (final PhaseType p : PhaseType.values()) {
            if (phase(p).equals(name)) {
                return p;
            }
        }
        return null;
    }
}
