/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks LLC
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCombatEnded;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What VisionSort's blunder survey judges, asked of Forge at the moment it holds ("facts": true on a play
 * request) - not worked out afterwards from the game record, which can only guess at rules (is it a land, could
 * it be played, how hard does that creature hit).
 *
 * Turns: each time an AI seat passes priority in its own second main phase with nothing on the stack - the end
 * of what it chose to do that turn - the lands it could still legally play (Forge's own land-play check, as the
 * table's actions use), the lands it played, its untapped lands, and the spells in its hand and command zone it
 * could pay for right then (Affordable: YES only, with a legal target), each with whether it could wait for an
 * opponent's turn (an instant or flash). The last such pass of a turn is the one kept.
 *
 * Combats: every attacker with its power, toughness, combat keywords and whether it's a commander, at the time
 * blocks were declared; its blockers the same way; each defending player's life, poison and commander damage
 * taken before damage; then who survived and the combat damage that reached each player.
 *
 * Nothing here changes the game: the checks run as the table's do (Affordable puts back the random generator and
 * the timestamp counter), so a game's state checksums are the same with facts on or off.
 */
final class Facts {
    private final Set<String> seats;                                   // the AI seats facts are kept for
    private final Map<String, JsonObject> turns = new LinkedHashMap<>(); // "turn:seat" -> its last end-of-main look
    private final JsonArray combats = new JsonArray();
    private Game game;
    private JsonObject combat;                                         // the one in progress
    private final Map<Integer, JsonObject> byId = new HashMap<>();     // its attackers and blockers, by card id
    private final Map<Integer, Integer> toPlayers = new HashMap<>();   // attacker id -> combat damage to players

    Facts(final Set<String> seats) {
        this.seats = seats;
    }

    /** Once the game exists (the seats' hooks, which call passed(), are made before it). */
    void attach(final Game g) {
        game = g;
        g.subscribeToEvents(this);
    }

    /** DecisionHook: an AI seat passed priority (chooseSpellAbilityToPlay answered nothing). */
    void passed(final Player ai) {
        final Game game = ai.getGame();
        final PhaseHandler ph = game.getPhaseHandler();
        if (!seats.contains(ai.getName()) || ph.getPlayerTurn() != ai || !ph.is(PhaseType.MAIN2)
                || !game.getStack().isEmpty()) {
            return;
        }
        final JsonObject t = new JsonObject();
        t.addProperty("turn", ph.getTurn());
        t.addProperty("seat", ai.getName());
        t.addProperty("lands_played", ai.getLandsPlayedThisTurn());
        t.addProperty("hand", ai.getCardsIn(ZoneType.Hand).size());
        int untapped = 0;
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            untapped += c.isLand() && !c.isTapped() ? 1 : 0;
        }
        t.addProperty("untapped_lands", untapped);
        final JsonArray lands = new JsonArray();
        final JsonArray castable = new JsonArray();
        for (final ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Command}) {
            for (final Card c : ai.getCardsIn(z)) {
                boolean landListed = false;
                boolean spellListed = false;
                for (final SpellAbility sa : c.getAllPossibleAbilities(ai, true)) {
                    if (sa.isLandAbility() && !landListed) {
                        lands.add(card(c));
                        landListed = true;
                    } else if (sa.isSpell() && !spellListed && Affordable.targetable(sa)
                            && Affordable.of(sa, ai) == Affordable.Verdict.YES) {
                        final JsonObject s = new JsonObject();
                        s.addProperty("card", card(c));
                        s.addProperty("cmc", c.getCMC());
                        s.addProperty("kind", c.isInstant() || c.hasKeyword(Keyword.FLASH) ? "instant"
                                : c.isPermanent() ? "permanent" : "sorcery");
                        s.addProperty("commander", z == ZoneType.Command);
                        // an X spell is "castable" at X = 0, which often does nothing (seen: Sylvan Offering on turn 2)
                        s.addProperty("x", sa.getPayCosts() != null && sa.getPayCosts().hasXInAnyCostPart());
                        castable.add(s);
                        spellListed = true;
                    }
                }
            }
        }
        t.add("playable_lands", lands);
        t.add("castable", castable);
        turns.put(ph.getTurn() + ":" + ai.getName(), t);
    }

    @Subscribe
    public void onEvent(final GameEvent event) {
        if (event instanceof GameEventAttackersDeclared) {
            begin(game.getCombat());
        } else if (event instanceof GameEventBlockersDeclared) {
            blocks(game.getCombat());
        } else if (event instanceof GameEventPlayerDamaged d && d.combat() && d.source() != null) {
            toPlayers.merge(d.source().getId(), d.amount(), Integer::sum);
        } else if (event instanceof GameEventCombatEnded) {
            end();
        }
    }

    private void begin(final Combat c) {
        combat = null;
        byId.clear();
        toPlayers.clear();
        if (c == null || c.getAttackers().isEmpty()) {
            return;
        }
        combat = new JsonObject();
        combat.addProperty("turn", game.getPhaseHandler().getTurn());
        combat.addProperty("attacker", c.getAttackingPlayer().getName());
        final JsonObject defenders = new JsonObject();
        for (final Player p : game.getPlayers()) {
            if (p == c.getAttackingPlayer() || p.hasLost()) {
                continue;
            }
            final JsonObject d = new JsonObject();
            d.addProperty("life", p.getLife());
            d.addProperty("poison", p.getPoisonCounters());
            final JsonObject cmd = new JsonObject();
            for (final Map.Entry<Card, Integer> e : p.getCommanderDamage()) {
                if (e.getValue() > 0) {
                    cmd.addProperty(card(e.getKey()), e.getValue());
                }
            }
            d.add("commander_damage", cmd);
            defenders.add(p.getName(), d);
        }
        combat.add("defenders", defenders);
        final JsonArray attackers = new JsonArray();
        for (final Card a : c.getAttackers()) {
            final JsonObject o = creature(a);
            final GameEntity d = c.getDefenderByAttacker(a);
            o.addProperty("defender", d == null ? "?" : d instanceof Card dc ? card(dc) : d.getName());
            o.addProperty("defending_player", d instanceof Card dc ? dc.getController().getName()
                    : d == null ? "?" : d.getName());
            o.add("blockers", new JsonArray());
            attackers.add(o);
            byId.put(a.getId(), o);
        }
        combat.add("attackers", attackers);
    }

    private void blocks(final Combat c) {
        if (combat == null || c == null) {
            return;
        }
        for (final Card a : c.getAttackers()) {
            final JsonObject o = byId.get(a.getId());
            if (o == null) {
                continue;
            }
            final JsonArray list = new JsonArray();
            for (final Card b : c.getBlockers(a)) {
                final JsonObject bo = creature(b);
                list.add(bo);
                byId.put(b.getId(), bo);
            }
            o.add("blockers", list);
            o.addProperty("power", a.getNetCombatDamage());     // again: a pump before blocks counts
        }
    }

    private void end() {
        if (combat == null) {
            return;
        }
        final Set<Integer> onField = new HashSet<>();
        for (final Card c : game.getCardsIn(ZoneType.Battlefield)) {
            onField.add(c.getId());
        }
        for (final Map.Entry<Integer, JsonObject> e : byId.entrySet()) {
            e.getValue().addProperty("survived", onField.contains(e.getKey()));
        }
        for (final var a : combat.getAsJsonArray("attackers")) {
            final JsonObject o = a.getAsJsonObject();
            o.addProperty("to_players", toPlayers.getOrDefault(o.get("id").getAsInt(), 0));
        }
        combats.add(combat);
        combat = null;
        byId.clear();
        toPlayers.clear();
    }

    private static JsonObject creature(final Card c) {
        final JsonObject o = new JsonObject();
        o.addProperty("card", card(c));
        o.addProperty("id", c.getId());
        o.addProperty("power", c.getNetCombatDamage());
        o.addProperty("toughness", c.getNetToughness() - c.getDamage());     // what's left of it
        o.addProperty("commander", c.isCommander());
        final JsonArray kw = new JsonArray();
        if (c.hasDoubleStrike()) {
            kw.add("double strike");
        } else if (c.hasFirstStrike()) {
            kw.add("first strike");
        }
        for (final Keyword k : new Keyword[] {Keyword.TRAMPLE, Keyword.DEATHTOUCH, Keyword.LIFELINK,
                Keyword.INDESTRUCTIBLE}) {
            if (c.hasKeyword(k)) {
                kw.add(k.toString().toLowerCase(java.util.Locale.ROOT));
            }
        }
        o.add("keywords", kw);
        return o;
    }

    private static String card(final Card c) {
        return c.getName() + "#" + c.getId();
    }

    JsonObject toJson() {
        final JsonObject o = new JsonObject();
        final JsonArray t = new JsonArray();
        turns.values().forEach(t::add);
        o.add("turns", t);
        o.add("combats", combats);
        return o;
    }
}
