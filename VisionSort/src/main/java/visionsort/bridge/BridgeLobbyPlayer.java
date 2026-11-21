/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks LLC
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * An AI seat whose controller is the hooked one. Mirrors LobbyPlayerAi.createControllerFor, which is
 * private; the AI profile is set explicitly (GamePlayerUtil.createAiPlayer would read it from
 * Forge's preferences and might save them).
 *
 * Forge's look-ahead copies the game with the same lobby players, so its imagined playouts get
 * hooked controllers too; DecisionHook passes those straight through.
 */
final class BridgeLobbyPlayer extends LobbyPlayerAi {
    private final DecisionHook hook;

    BridgeLobbyPlayer(final String name, final String profile, final DecisionHook hook) {
        super(name, null);
        setAiProfile(profile == null || profile.isEmpty() ? "Default" : profile);
        this.hook = hook;
    }

    @Override
    public Player createIngamePlayer(final Game game, final int id) {
        final Player p = new Player(getName(), game, id);
        p.setFirstController(controllerFor(p));
        return p;
    }

    @Override
    public PlayerController createMindSlaveController(final Player master, final Player slave) {
        return controllerFor(slave);
    }

    private HookedControllerAi controllerFor(final Player p) {
        final HookedControllerAi c = new HookedControllerAi(p.getGame(), p, this, hook);
        c.getAi().setUseSimulation(null);   // as LobbyPlayerAi does with no AIOption
        return c;
    }
}
