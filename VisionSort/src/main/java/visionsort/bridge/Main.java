/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import forge.GuiDesktop;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.util.BuildInfo;

/**
 * Starts Forge headless and serves VisionSort's requests: one JSON object per line on stdin, one
 * JSON object per line on stdout (see {@link Protocol}).
 *
 * Why not Forge's own forge.view.Main: it turns on Sentry crash reporting, which sends errors to
 * Forge's server. The bridge sets up only what a headless game needs, so nothing leaves the PC.
 *
 * Forge prints freely (card loading, warnings, AI notes). The protocol owns the real stdout, and
 * System.out is pointed at stderr before Forge loads, so none of that can corrupt a message.
 * Run with Forge's folder as the working directory: Forge finds res/ relative to it.
 */
public final class Main {
    private Main() {}

    public static void main(final String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        final PrintStream protocolOut =
                new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        final Protocol protocol = new Protocol(in, protocolOut);

        int status = 1;
        try {
            final long t0 = System.nanoTime();
            GuiBase.setInterface(new GuiDesktop());
            FModel.initialize(null, null);
            protocol.ready(BuildInfo.getVersionString(), (System.nanoTime() - t0) / 1_000_000, repeatable());
            protocol.serve();
            status = 0;
        } catch (Throwable t) {
            t.printStackTrace();   // start-up failed, or stdin broke: VisionSort sees the process end
        } finally {
            System.exit(status);   // always: Forge leaves non-daemon threads behind that would keep the JVM up
        }
    }

    /**
     * Whether games in this process are a pure function of their seed, decks and settings.
     *
     * Forge's AI walks some hash sets of objects that use Java's identity hash, and HotSpot draws
     * identity hashes from per-thread random state that depends on every thread created before. A
     * fresh process repeats a seeded game exactly, but in a warm one the previous games shift those
     * hashes and a later game can play out differently (seen: seed 101 replayed after three other
     * games split at line 453 of 505). -XX:hashCode=2 gives every object the same identity hash, so
     * set orders depend only on what was put in them: then every game repeats, first or fiftieth.
     */
    static boolean repeatable() {
        return java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
                .contains("-XX:hashCode=2");
    }
}
