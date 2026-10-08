package com.schaltli.android

/**
 * What this app can read of a project (the designer's lib/system-generation.ts),
 * announced in its hello and declared in its DDF - one constant for both, so
 * the two cannot disagree.
 *
 * 1.1: reads the dark variant (XDark) beside every field and follows
 * schaltli/state/theme (the designer's docs/device-contract.md §2.3, §4).
 * 1.2: resolves the placeholders in a text - topics, device model and id, the
 * project's separators (§2.4). The designer warns before deploying a project
 * with placeholders to a device below it.
 * 1.3: opens and closes popups - popups[], popupFence, open-popup, close-popup
 * (§2.5). The designer warns before deploying a project whose buttons open a
 * popup to a device below it.
 * 1.4: draws live values and combined topics - liveText, liveValues,
 * liveIconId, combinedTopics[] (§2.6). The designer warns before deploying a
 * project with live values to a device below it.
 * 1.5: draws the navigator and pages past hidden screens - navigators[],
 * navigatorId, hidden (§2.7).
 */
const val SYSTEM_GENERATION = "1.5"
