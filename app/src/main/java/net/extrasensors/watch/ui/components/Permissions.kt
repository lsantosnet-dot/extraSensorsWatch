package net.extrasensors.watch.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import androidx.wear.compose.foundation.LocalScreenIsActive
import net.extrasensors.watch.Permissions

/** Texto para quando o serviço de fundo não está rodando. */
fun serviceStoppedText(context: android.content.Context): String =
    if (!Permissions.notificationsGranted(context)) "Permita as notificações do app para medir em segundo plano."
    else "Serviço parado: abra o app para retomar."

/** true quando a tela está visível e o app em primeiro plano. */
@Composable
fun rememberResumed(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state.isAtLeast(Lifecycle.State.RESUMED)
}

/** Relógio que atualiza a cada [periodMs]. */
@Composable
fun rememberNow(periodMs: Long = 1_000): Long {
    val now by produceState(System.currentTimeMillis(), periodMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return now
}

/**
 * Pede as permissões no contexto da tela. Mostra explicação e botão "Permitir";
 * se o sistema não mostrar mais o pedido, o botão abre as configurações do app.
 */
@Composable
fun PermissionGate(
    label: String,
    color: Color,
    required: List<String>,
    optional: List<String> = emptyList(),
    rationale: String,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val resumed = rememberResumed()
    var refresh by remember { mutableLongStateOf(0L) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val granted = remember(refresh, resumed) { required.all { Permissions.granted(context, it) } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        refresh++
    }
    // O pager compõe as páginas vizinhas: só pede quando esta página está de fato na tela.
    val onScreen = LocalScreenIsActive.current && resumed
    LaunchedEffect(onScreen) {
        if (onScreen && !granted && !asked) launcher.launch((required + optional).toTypedArray())
    }
    if (granted) {
        content()
        return
    }
    val blocked = asked && activity != null &&
        required.none { activity.shouldShowRequestPermissionRationale(it) }
    StateMessage(
        label = label,
        color = color,
        title = "Permissão necessária",
        body = rationale,
        actionText = if (blocked) "Abrir configurações" else "Permitir",
        onAction = {
            if (blocked) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                )
            } else {
                launcher.launch((required + optional).toTypedArray())
            }
        },
    )
}
