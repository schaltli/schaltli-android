package com.schaltli.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * What a phone shows before it has a project.
 *
 * Two ways forward, not one. A bundle can be imported from a file, and that is
 * what this screen used to offer - but the ordinary way a project arrives is
 * over MQTT from the designer, and that needs a broker this phone has been
 * told about. Until 2026-09-23 the settings screen sat behind "has a project",
 * so a fresh install could not reach the one screen that would let it receive
 * anything: the way in was closed by the thing it was the way in to.
 */
@Composable
fun ImportScreen(
    errorMessage: String?,
    onBundleSelected: (android.net.Uri) -> Unit,
    onConfigureBroker: () -> Unit,
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
        // The mark plays itself once instead of a title in the app's font:
        // this is the one screen a person looks at while nothing else is
        // happening, so it is the one place the animation belongs.
        SchaltliIntro(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .aspectRatio(BrandGlyphs.WIDTH / (BrandGlyphs.HEIGHT + 44f))
                .padding(bottom = 16.dp),
            ink = MaterialTheme.colorScheme.onBackground,
            knock = MaterialTheme.colorScheme.background,
        )
        Text(
            text = "Dieses Telefon hat noch kein Projekt.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "Ein Projekt kommt normalerweise über den Broker vom Designer. " +
                "Dafür muss das Telefon wissen, wo der Broker steht.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onConfigureBroker) {
            Text("MQTT-Verbindung einrichten…")
        }
        OutlinedButton(
            onClick = { pickBundle.launch(arrayOf("application/zip", "application/octet-stream")) },
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Text("Projekt aus Datei laden…")
        }
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
