package it.topcash.studio.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import it.topcash.studio.R
import it.topcash.studio.data.AppViewModel
import kotlinx.coroutines.launch

@Composable private fun AuthFrame(content: @Composable () -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize()) {
        // soft brand aurora behind the form
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawRect(bg)
            val a = androidx.compose.ui.geometry.Offset(size.width * 0.15f, 0f); val b = androidx.compose.ui.geometry.Offset(size.width, size.height)
            drawCircle(Brush.radialGradient(listOf(BrandBlue.copy(alpha = 0.18f), BrandBlue.copy(alpha = 0f)), center = a, radius = size.width * 0.9f), radius = size.width * 0.9f, center = a)
            drawCircle(Brush.radialGradient(listOf(BrandRed.copy(alpha = 0.10f), BrandRed.copy(alpha = 0f)), center = b, radius = size.width * 0.8f), radius = size.width * 0.8f, center = b)
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { content() }
    }
}

/** Brand tile: pops in with a spring; shrinks smoothly when the keyboard is open or the screen is short. */
@Composable private fun Logo() {
    val s = remember { Animatable(0.6f) }; val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { launch { a.animateTo(1f) }; s.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
    val ime = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val target = when { ime -> 52.dp; screenH < 640 -> 68.dp; screenH > 900 -> 104.dp; else -> 88.dp }
    val size by androidx.compose.animation.core.animateDpAsState(target, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow), label = "logo")
    Image(painterResource(R.drawable.logo_tile), "Top Cash Service", Modifier.size(size).graphicsLayer { scaleX = s.value; scaleY = s.value; alpha = a.value })
}

@Composable fun SetupScreen(vm: AppViewModel) {
    // pre-filled with the office server; any other address (or https://…) can be typed
    var address by rememberSaveable { mutableStateOf(vm.server.removePrefix("http://").trimEnd('/').removeSuffix(":8470").ifBlank { "192.168.1.220" }) }
    var external by rememberSaveable { mutableStateOf(vm.serverExternal.removePrefix("https://").trimEnd('/')) }
    var error by remember { mutableStateOf<String?>(null) }
    AuthFrame {
        Logo()
        Text("Top Cash Studio", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 22.dp).appear(1))
        Text("Collega l'app al server dell'ufficio.\nL'indirizzo è nelle Impostazioni di Top Cash Studio sul PC.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 28.dp).appear(2))
        OutlinedTextField(address, { address = it; error = null }, Modifier.fillMaxWidth().widthIn(max = 460.dp).appear(3), label = { Text("Indirizzo del server") },
            placeholder = { Text("192.168.1.220") }, leadingIcon = { Icon(Icons.Rounded.Router, null) }, singleLine = true, isError = error != null,
            supportingText = { Text(error ?: "La porta 8470 viene aggiunta in automatico") }, shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { error = vm.setServer(address, external) }))
        OutlinedTextField(external, { external = it; error = null }, Modifier.fillMaxWidth().widthIn(max = 460.dp).padding(top = 4.dp).appear(3), label = { Text("Indirizzo esterno (facoltativo)") },
            placeholder = { Text("topcash.tuodominio.it") }, leadingIcon = { Icon(Icons.Rounded.Public, null) }, singleLine = true,
            supportingText = { Text("Per usare l'app anche fuori ufficio") }, shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { error = vm.setServer(address, external) }))
        Button({ error = vm.setServer(address, external) }, Modifier.fillMaxWidth().widthIn(max = 460.dp).height(54.dp).padding(top = 6.dp).appear(4), shape = MaterialTheme.shapes.medium) { Text("Collega") }
        Text("In ufficio usa il Wi-Fi, fuori passa da solo all'indirizzo esterno. I dati restano sul server aziendale.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp).appear(5))
    }
}

@Composable fun LoginScreen(vm: AppViewModel) {
    var user by rememberSaveable { mutableStateOf(vm.lastUser) }
    var pass by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val submit = { if (!busy && user.isNotBlank() && pass.isNotBlank()) { busy = true; error = null; scope.launch { error = vm.login(user, pass); busy = false; if (error != null) pass = "" } } }
    AuthFrame {
        Logo()
        Text("Accedi", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 22.dp).appear(1))
        Text(vm.server.removePrefix("http://").trimEnd('/'), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 26.dp).appear(2))
        OutlinedTextField(user, { user = it; error = null }, Modifier.fillMaxWidth().widthIn(max = 460.dp).appear(3), label = { Text("Nome utente") }, leadingIcon = { Icon(Icons.Rounded.Person, null) },
            singleLine = true, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false))
        OutlinedTextField(pass, { pass = it; error = null }, Modifier.fillMaxWidth().widthIn(max = 460.dp).padding(top = 10.dp).appear(4), label = { Text("Password") }, leadingIcon = { Icon(Icons.Rounded.Lock, null) },
            trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Nascondi" else "Mostra") } },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true, isError = error != null,
            supportingText = { error?.let { Text(it) } }, shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { submit() }))
        Button({ submit() }, Modifier.fillMaxWidth().widthIn(max = 460.dp).height(54.dp).padding(top = 4.dp).appear(5), enabled = !busy, shape = MaterialTheme.shapes.medium) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary) else Text("Accedi")
        }
        TextButton({ vm.changeServer() }, Modifier.padding(top = 12.dp)) { Text("Cambia server") }
    }
}

/** Outside the office: shown when an administrator blocked this phone (Utenti → Dispositivi), or briefly while it is checked. */
@Composable fun DeviceScreen(vm: AppViewModel) {
    val d by vm.device.collectAsStateWithLifecycle()
    val blocked = d.status == "revoked"
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(blocked) { if (!blocked) while (true) { kotlinx.coroutines.delay(8000); if (vm.checkDevice()) break } }
    AuthFrame {
        Logo()
        Text(if (blocked) "Dispositivo bloccato" else "Verifica del telefono", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 22.dp).appear(1))
        Text(if (blocked) "L'amministratore ha bloccato l'accesso da fuori ufficio per questo telefono. Se è un errore, comunicagli questo codice: può sbloccarlo da Utenti → Dispositivi."
             else "Sto verificando questo telefono con il server dell'ufficio.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp).padding(top = 8.dp, bottom = 22.dp).appear(2))
        if (d.code.isNotEmpty()) androidx.compose.foundation.layout.Row(
            Modifier.appear(3).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 26.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.VerifiedUser, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("  " + d.code, style = MaterialTheme.typography.headlineMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, letterSpacing = androidx.compose.ui.unit.TextUnit(3f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        if (!blocked) androidx.compose.foundation.layout.Row(Modifier.padding(top = 22.dp).appear(4), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("  Un attimo: si apre da solo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton({ checking = true; scope.launch { vm.checkDevice(); checking = false } }, Modifier.padding(top = 12.dp), enabled = !checking) { Text(if (checking) "Controllo…" else "Controlla ora") }
        TextButton({ vm.changeServer() }) { Text("Cambia server") }
    }
}
