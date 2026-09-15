package com.kojo.boilerplate.feature.profile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kojo.boilerplate.core.ui.layout.LabelledValue

/**
 * One captioned field of a profile: a card holding a label and the value it captions.
 *
 * Extracted here because there are two profile screens and they are the same screen. [ProfileScreen]
 * is the phone's, [ProfileDetailPane] is the detail half of the list-detail layout, and the two
 * carried a character-identical copy of this card each — so the pair was already the thing that
 * makes a widened window worth designing for, kept in step by hand.
 *
 * The layout is [LabelledValue] rather than a `Column`, and the reason is exactly that pair: the
 * card is drawn across a phone in one screen and inside a pane split in the other, so how much room
 * the label and the value have is settled per instance by the measure pass and not by anything
 * either screen knows. See `docs/custom-layout.md`.
 */
@Composable
internal fun ProfileFieldCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        LabelledValue(
            label = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            value = {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            modifier = Modifier.padding(16.dp),
        )
    }
}
