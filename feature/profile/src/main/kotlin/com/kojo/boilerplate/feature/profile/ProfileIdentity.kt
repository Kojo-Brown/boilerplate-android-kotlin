package com.kojo.boilerplate.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kojo.boilerplate.core.ui.components.UserMonogram
import com.kojo.boilerplate.core.ui.transition.SharedElementKey
import com.kojo.boilerplate.core.ui.transition.SharedElementTransition
import com.kojo.boilerplate.core.ui.transition.sharedBoundsTransition
import com.kojo.boilerplate.core.ui.transition.sharedElementTransition

/**
 * The monogram and name at the top of a profile, and the destination half of the transition out of
 * the home list.
 *
 * ### Why it is extracted
 *
 * `ProfileScreen` and `ProfileDetailPane` each had their own copy of this block, and the copies
 * were identical. That was survivable while it was only duplication. It stops being survivable
 * once one of the two is the far end of a shared-element transition: the keys here have to be the
 * same keys the list row declares, and a second copy is a second place for that to drift — with
 * the symptom being not a build failure but a transition that silently does not happen. One
 * composable means there is one set of keys to get right.
 *
 * The pane passes `transition = null`, which is the other half of the same decision: in the
 * list-detail layout the row and this block are on screen simultaneously, so they cannot be two
 * halves of one transition. See `HomeTwoPaneScreen`.
 *
 * ### The two modifiers
 *
 * [UserMonogram] is the same composable the list row draws, at 80dp instead of 40dp, so the two
 * sides are one drawing at two magnifications and `sharedElement` is correct — it animates a
 * rectangle and cross-fades nothing.
 *
 * The name is the same string at a different type scale (`headlineSmall` here, `titleMedium` in the
 * row), so the two sides are genuinely different drawings and `sharedBounds` is the one that
 * applies: the box travels and the contents cross-fade inside it. `docs/shared-elements.md` has
 * the full argument for the split.
 */
@Composable
internal fun ProfileIdentity(
    profile: ProfileData,
    transition: SharedElementTransition?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        UserMonogram(
            displayName = profile.displayName,
            size = 80.dp,
            modifier = Modifier.sharedElementTransition(
                key = SharedElementKey.UserAvatar(profile.userId),
                transition = transition,
            ),
        )
        Text(
            text = profile.displayName,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.sharedBoundsTransition(
                key = SharedElementKey.UserName(profile.userId),
                transition = transition,
            ),
        )
    }
}
