package com.kojo.boilerplate.core.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/**
 * The camera torch control, shared by the two screens that have a camera.
 *
 * ### Why it is in the design system rather than in each feature
 *
 * `:feature:scanner` and `:feature:textrecognition` are siblings and neither may import the other,
 * so a control both need has exactly one place it can live — the same argument [UserMonogram]
 * carries at greater length. Both had a copy, and the copies had already diverged in nothing but
 * the event they raised, which is the state a shared control is cheapest to extract from.
 *
 * ### Why a toggle button rather than an `IconButton`
 *
 * What both copies had was an `IconButton` whose `contentDescription` flipped between "Enable
 * flash" and "Disable flash". That announces as a plain button, so a screen reader is told what
 * the *next* tap will do and never what the flash is doing now: there is no state in the
 * semantics tree to read back, nothing for TalkBack to announce when the state changes under a
 * finger that is still on the control, and nothing that survives the reader arriving at the
 * button for a second time. `IconToggleButton` puts the control on `Modifier.toggleable`, which
 * gives it a role and a checked state the platform reports — the accessibility service then
 * decides how to phrase it, which is the part an app should not be inventing.
 *
 * [stateDescription] replaces what the role would otherwise say. `Role.Checkbox` announces
 * "ticked" and "not ticked", which is a sentence about a checkbox; a torch is on or off.
 *
 * ### Why the icon shows the state rather than the action
 *
 * The copies this replaces drew [Icons.Default.FlashOff] *while the flash was on*, because the
 * icon named the action the button performed. That is a defensible convention for a plain button
 * and the wrong one here: a toggle that announces "Flash, On" while showing a struck-through
 * flash contradicts itself, and the contradiction lands hardest on a user who has both the icon
 * and the announcement. The checked colour from `IconButtonDefaults.iconToggleButtonColors` is
 * the second cue, so the state is not carried by colour alone either.
 *
 * @param flashOn whether the torch is currently lit.
 * @param onToggle raised on every press. Takes no argument because the state is hoisted — the
 *   caller's view model already knows what the flash is doing, and a `Boolean` here would be a
 *   second copy of that for the caller to agree with.
 */
@Composable
fun FlashToggle(
    flashOn: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconToggleButton(
        checked = flashOn,
        onCheckedChange = { onToggle() },
        // On the same layout node as the `toggleable` inside `IconToggleButton`, so this lands in
        // the same semantics configuration rather than on a node above it.
        modifier = modifier.semantics {
            stateDescription = if (flashOn) "On" else "Off"
        },
    ) {
        Icon(
            imageVector = if (flashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
            contentDescription = "Flash",
        )
    }
}
