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
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;

/**
 * What an AI seat may see that it otherwise couldn't: its opponents' hands, and the top cards of their
 * libraries - VisionSort's "Omniscient" and "Cheater" skills. A seat's "peek": {"hand": true, "library": 3}.
 *
 * Forge's look-ahead (2.0.16's flat Monte Carlo) plays out copies of the game, and first shuffles every card
 * the AI can't see (HiddenInfoShuffler: whatever CardView.canBeShownTo says is hidden from it), so it plans
 * against guesses. Around each of the seat's decisions the cards it may peek at get Forge's own temporary
 * "may look" (as "look at the top card" effects do): the shuffler leaves them where they are, and its other
 * choices can use them too. Taken away again after the decision, so nobody else's view changes; the same
 * cards each time, so games still repeat from their seed.
 */
record Peek(boolean hand, int library) {
    static final Peek NONE = new Peek(false, 0);
    static final boolean DIAG = Boolean.getBoolean("visionsort.peek.diag");

    static Peek from(final JsonObject seat) {
        if (!seat.has("peek")) {
            return NONE;
        }
        final JsonObject p = seat.getAsJsonObject("peek");
        final int library = p.has("library") ? p.get("library").getAsInt() : 0;
        if (library < 0) {
            throw new IllegalArgumentException("peek's library must be 0 or more, not " + library);
        }
        return new Peek(p.has("hand") && p.get("hand").getAsBoolean(), library);
    }

    boolean any() {
        return hand || library > 0;
    }

    /** Let ai look at what it may peek at; returns the cards, for {@link #hide}. */
    List<Card> show(final Game game, final Player ai) {
        final List<Card> shown = new ArrayList<>();
        for (final Player opponent : game.getPlayers()) {
            if (opponent == ai || opponent.hasLost()) {
                continue;
            }
            if (hand) {
                shown.addAll(opponent.getCardsIn(ZoneType.Hand));
            }
            int n = 0;
            for (final Card c : opponent.getCardsIn(ZoneType.Library)) {    // the top is first, as Forge draws
                if (n++ >= library) {
                    break;
                }
                shown.add(c);
            }
        }
        for (final Card c : shown) {
            c.addMayLookTemp(ai);
        }
        if (DIAG && !shown.isEmpty()) {
            final Card c = shown.get(0);
            System.err.println("PEEKDIAG frozen=" + game.getTracker().isFrozen() + " zone=" + c.getZone().getZoneType()
                    + " shown=" + c.getView().canBeShownTo(ai.getView()) + " n=" + shown.size());
        }
        return shown;
    }

    static void hide(final List<Card> shown, final Player ai) {
        for (final Card c : shown) {
            c.removeMayLookTemp(ai);
        }
    }
}
