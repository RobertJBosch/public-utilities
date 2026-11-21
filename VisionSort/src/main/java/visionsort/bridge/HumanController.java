/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks LLC
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;

/**
 * Forge's human player with VisionSort's two setup options:
 *  - who plays first, when the game names a seat: Forge flips for it and asks the winner; if that's
 *    you, the named seat is returned without asking (an AI winner is handled by DecisionHook);
 *  - the practice option of redrawing your opening hand for free, before any mulligan: the hand goes
 *    back, the library is shuffled and the same number drawn, then the keep / mulligan question comes
 *    again. On the game thread, as Forge's own Serum Powder redraw is.
 */
final class HumanController extends PlayerControllerHuman {
    private final HumanGame owner;

    HumanController(final Game game, final Player p, final LobbyPlayer lp, final HumanGame owner) {
        super(game, p, lp);
        this.owner = owner;
    }

    @Override
    public Player chooseStartingPlayer(final boolean isFirstGame) {
        final Player forced = owner.forcedFirst();
        return forced != null ? forced : super.chooseStartingPlayer(isFirstGame);
    }

    @Override
    public boolean mulliganKeepHand(final Player mulliganingPlayer, final int cardsToReturn) {
        while (true) {
            final boolean keep = super.mulliganKeepHand(mulliganingPlayer, cardsToReturn);
            final HumanSeat seat = owner.seat();
            if (seat == null || !seat.takeRedraw()) {
                return keep;
            }
            final Player me = getPlayer();
            final CardCollection hand = new CardCollection(me.getCardsIn(ZoneType.Hand));
            for (final Card c : hand) {
                getGame().getAction().moveToLibrary(c, null);
            }
            me.shuffle(null);
            me.drawCards(hand.size());
            owner.note("You redraw your opening hand (practice)");
        }
    }

    /** The human seat in the lobby: its in-game player gets a HumanController. */
    static final class Lobby extends LobbyPlayerHuman {
        private final HumanGame owner;

        Lobby(final String name, final HumanGame owner) {
            super(name);
            this.owner = owner;
        }

        @Override
        public Player createIngamePlayer(final Game game, final int id) {
            final Player p = new Player(getName(), game, id);
            p.setFirstController(new HumanController(game, p, this, owner));
            return p;
        }
    }
}
