/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

/**
 * Looks at what Forge's AI chose for a top-level decision and may answer differently. A policy may
 * only return a legal answer: one of the options Forge offered, or a choice that is always
 * allowed (passing priority, declining an optional action).
 *
 * Returning the AI's own object unchanged (the same reference) means "no change"; DecisionHook
 * compares references to tell.
 */
interface Policy {
    Policy NONE = new Policy() {
        @Override
        public String name() {
            return "none";
        }

        @Override
        public Object adjust(final String decision, final Object[] args, final Object aiChoice) {
            return aiChoice;
        }
    };

    String name();

    Object adjust(String decision, Object[] args, Object aiChoice);
}
