/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks LLC
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;

/**
 * A "lucky" seat's shuffles (VisionSort's Cheater skill): every time its library is shuffled, the cards come out
 * in an order a player would wish for - lands when it needs them, and an opening hand it can cast.
 *
 * Forge has a cheat of its own (AiController.cheatShuffle, "mana weave"), but it was made for 40- and 60-card
 * decks: it plants seven lands near the top and puts every other land at the bottom, so a 100-card Commander deck
 * would draw no land at all after its seventh. Here instead:
 *  - the opening hand is 3 lands and 4 spells, the spells ones it can cast early (mana value 3 or less) when the
 *    deck has them;
 *  - then lands and spells alternate until it has 7 lands (in hand and in play), and after that one card in three
 *    is a land - never screwed, never flooded;
 *  - otherwise spells come in Forge's own shuffled order. (The first version dealt every spell cheapest first:
 *    in a 100-card deck it drew 1- and 2-drops all game while its best cards sat at the bottom, and it won no
 *    more than Hardest - 14 of 30 on the 2026-11-24 ladder.)
 * A shuffle later in the game (a tutor, a fetch land) keeps to the same rhythm from the lands it has by then.
 * The arrangement starts from Forge's own shuffle (the order within lands and within spells is Forge's), so a game
 * still repeats from its seed.
 *
 * A seat's "lucky": true (or "always") arranges every shuffle - Cheater. "lucky": "opening" arranges only the
 * shuffles before the game starts (the first, and a mulligan's) and leaves the rest to Forge: a good start, then
 * fair draws. Measured 1v1 on 6 decks against Hardest (2026-11-25/26): every shuffle lucky won 63%, and with the
 * peek as well 79% (Cheater); the peek with a lucky opening only, 49% - no stronger than Hardest, so the rhythm of
 * the whole game is what counts, not the opening hand.
 */
final class Lucky {
    enum Mode {
        OFF, OPENING, ALWAYS;

        static Mode from(final JsonObject seat) {
            if (!seat.has("lucky") || seat.get("lucky").isJsonNull()) {
                return OFF;
            }
            final JsonPrimitive v = seat.getAsJsonPrimitive("lucky");
            if (v.isBoolean()) {
                return v.getAsBoolean() ? ALWAYS : OFF;
            }
            return switch (v.getAsString()) {
                case "opening" -> OPENING;
                case "always" -> ALWAYS;
                case "off" -> OFF;
                default -> throw new IllegalArgumentException(
                        "lucky must be true, false, \"opening\" or \"always\", not " + v);
            };
        }

        /** Whether this shuffle of ai's library is arranged. */
        boolean arranges(final Player ai) {
            return this == ALWAYS || this == OPENING && opening(ai);
        }
    }

    static final int OPENING = 7;
    static final int OPENING_LANDS = 3;
    static final int ENOUGH_LANDS = 7;
    static final int LAND_EVERY_LATE = 3;
    static final int OPENING_SPELLS = OPENING - OPENING_LANDS;
    static final int CHEAP = 3;                   // an opening hand's spells: mana value at most this

    private Lucky() {
    }

    static CardCollectionView arrange(final CardCollectionView shuffled, final Player ai) {
        final List<Card> lands = new ArrayList<>();
        final List<Card> spells = new ArrayList<>();
        for (final Card c : shuffled) {
            (c.isLand() ? lands : spells).add(c);
        }
        final int have = opening(ai) ? -1 : countLands(ai);
        if (have < 0) {                                             // the opening hand: cheap spells to the front
            final List<Card> cheap = new ArrayList<>();
            for (final Card c : spells) {
                if (cheap.size() < OPENING_SPELLS && c.getCMC() <= CHEAP) {
                    cheap.add(c);
                }
            }
            spells.removeAll(cheap);
            spells.addAll(0, cheap);
        }
        final CardCollection out = new CardCollection();
        int landsSoFar = Math.max(0, have);
        int sinceLand = 0;
        for (int i = 0; !lands.isEmpty() || !spells.isEmpty(); i++) {
            final boolean wantLand;
            if (have < 0 && i < OPENING) {
                wantLand = i % 2 == 0 && landsSoFar < OPENING_LANDS;   // L S L S L S S
            } else if (landsSoFar < ENOUGH_LANDS) {
                wantLand = sinceLand >= 1;                              // alternate
            } else {
                wantLand = sinceLand >= LAND_EVERY_LATE - 1;            // one in three
            }
            final boolean land = wantLand ? !lands.isEmpty() : spells.isEmpty();
            out.add(land ? lands.remove(0) : spells.remove(0));
            if (land) {
                landsSoFar++;
                sinceLand = 0;
            } else {
                sinceLand++;
            }
        }
        return out;
    }

    /** Before the game starts: nothing in hand or in play (a mulligan puts the hand back first). */
    static boolean opening(final Player ai) {
        return ai.getCardsIn(ZoneType.Hand).isEmpty() && ai.getCardsIn(ZoneType.Battlefield).isEmpty();
    }

    private static int countLands(final Player ai) {
        int n = 0;
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            n += c.isLand() ? 1 : 0;
        }
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            n += c.isLand() ? 1 : 0;
        }
        return n;
    }
}
