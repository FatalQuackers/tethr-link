package app.pixlink.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Settings section: optional sign-in. */
@Composable
fun AccountSection() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Account.load(ctx) }
    val email by Account.email.collectAsState()
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (email != null) {
            Text("Signed in as $email", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Add a PC from your account without scanning a QR code: tap \"Add a PC\", then \"Add from my account\".",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { scope.launch { Account.signOut(ctx) } }) { Text("Sign out") }
        } else {
            Text(
                "Optional. Tethr Link works fully without an account. Signing in lets you add a PC from your account, no QR code needed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { Account.signInWithGoogle(ctx) }, enabled = !busy) { Text("Sign in with Google") }
            OutlinedTextField(
                typed, { typed = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Or sign in with your email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            OutlinedButton(enabled = !busy && typed.contains('@'), onClick = {
                busy = true
                scope.launch {
                    status = try {
                        Account.sendEmailLink(ctx, typed)
                        "We emailed a sign-in link to ${typed.trim()}. Open it on this phone to finish."
                    } catch (e: Account.AccountException) { "Couldn't send the link: ${e.message}" }
                    busy = false
                }
            }) { Text("Email me a link") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Lists the PCs in your account; tapping one pairs with it like scanning its QR code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPcsScreen(onPick: (uri: String, name: String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val email by Account.email.collectAsState()
    var pcs by remember { mutableStateOf<List<Account.Pc>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(email, reload) {
        Account.load(ctx)
        if (email == null) return@LaunchedEffect
        pcs = null; error = null
        try { pcs = Account.pcs(ctx) } catch (e: Account.AccountException) { error = e.message; pcs = emptyList() }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Add from my account") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                email == null -> Column(Modifier.padding(4.dp)) { AccountSection() }
                pcs == null -> Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                }
                pcs!!.isEmpty() -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(error ?: "No PCs in your account yet.", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "On your PC, open Tethr Link → Settings → Account and sign in with the same account ($email).",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { reload++ }) { Text("Check again") }
                }
                else -> LazyColumn {
                    items(pcs!!, key = { it.id }) { pc ->
                        val uri = pc.pairingUri()
                        ListItem(
                            headlineContent = { Text(pc.name) },
                            supportingContent = { Text(if (uri != null) "Tap to add" else "Open Tethr Link on this PC, then check again") },
                            leadingContent = { Icon(Icons.Outlined.Computer, null) },
                            modifier = Modifier.clickable(enabled = uri != null) { onPick(uri!!, pc.name) },
                        )
                    }
                }
            }
        }
    }
}
