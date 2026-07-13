package com.kojo.boilerplate.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kojo.boilerplate.feature.profile.ProfileDetailPane

@Composable
fun HomeTwoPaneScreen(
    selectedUserId: String?,
    onUserSelected: (String?) -> Unit,
    onNavigateToBarcodeScanner: () -> Unit,
    onNavigateToTextRecognition: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxSize()) {
        HomeScreen(
            onNavigateToProfile = { userId -> onUserSelected(userId) },
            onNavigateToBarcodeScanner = onNavigateToBarcodeScanner,
            onNavigateToTextRecognition = onNavigateToTextRecognition,
            modifier = Modifier.weight(0.4f),
        )

        VerticalDivider(modifier = Modifier.fillMaxHeight())

        Box(
            modifier = Modifier
                .weight(0.6f)
                .fillMaxHeight(),
        ) {
            if (selectedUserId != null) {
                ProfileDetailPane(userId = selectedUserId)
            } else {
                HomeTwoPaneEmptyDetail(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun HomeTwoPaneEmptyDetail(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Default.PersonSearch,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Select a user to view their profile",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
