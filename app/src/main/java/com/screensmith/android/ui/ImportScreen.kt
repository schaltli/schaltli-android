package com.screensmith.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ImportScreen(
    errorMessage: String?,
    onBundleSelected: (android.net.Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pickBundle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onBundleSelected)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Screensmith", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Import a project exported from the Screensmith designer's \"Android Phone\" device to get started.",
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = { pickBundle.launch(arrayOf("application/zip", "application/octet-stream")) }) {
            Text("Import Project…")
        }
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
