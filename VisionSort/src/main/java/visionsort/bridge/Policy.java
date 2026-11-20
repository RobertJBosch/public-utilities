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
 * Looks at what Forge's AI chose for a decision and may answer differently. A policy may only
 * return a legal answer: one of the options Forge offered, a choice that is always allowed
 * (passing priority, declining an optional action), or a change Forge's own validator accepts.
 *
 * Return values:
 *  - the AI's own object (the same reference): no change;
 *  - {@link #CHANGED_IN_PLACE}: the policy changed the AI's answer where it lies (a combat, the
 *    targets of the chosen spell); the decision still returns the AI's object;
 *  - anything else: the new answer.
 *
 * nested: the decision was asked while the seat was deciding something else (paying a cost,
 * resolving an effect). Policies should only change nested decisions of kinds that stay legal on
 * their own.
 */
interface Policy {
    Object CHANGED_IN_PLACE = new Object();

    Policy NONE = new Policy() {
        @Override
        public String name() {
            return "none";
        }

        @Override
        public Object adjust(final String decision, final Object[] args, final Object aiChoice, final boolean nested) {
            return aiChoice;
        }
    };

    String name();

    Object adjust(String decision, Object[] args, Object aiChoice, boolean nested);
}
