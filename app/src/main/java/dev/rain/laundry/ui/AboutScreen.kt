package dev.rain.laundry.ui

import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.*
import dev.rain.laundry.ui.ControlIcon as Icon
import dev.rain.laundry.i18n.LocalizedText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.rain.laundry.BuildConfig
import dev.rain.laundry.R
import dev.rain.laundry.data.UsageDocuments
import kotlinx.coroutines.launch

private const val SOURCE_URL = "https://github.com/xiyuchengxu/jianxi"
private const val EASTER_EGG_URL = "https://d.bluepoch.com/home/Reverse1999_Bluepoch_1019.apk"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, pageColor: Color = MaterialTheme.colorScheme.background,
    modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val documents = remember(context) { UsageDocuments.load(context) }
    val snackbar = remember { SnackbarHostState() }
    var licenseOpen by rememberSaveable { mutableStateOf(false) }
    var colorLicenseOpen by rememberSaveable { mutableStateOf(false) }
    var fontLicenseOpen by rememberSaveable { mutableStateOf(false) }
    var donationOpen by rememberSaveable { mutableStateOf(false) }
    var documentOpen by rememberSaveable { mutableStateOf("") }

    AppScaffold(
        modifier = modifier,
        containerColor = pageColor,
        topBar = {
            AppTopAppBar(
                title = { Text(appText(R.string.msg_c1cb5f40de7c)) },
                titleText = appText(R.string.msg_c1cb5f40de7c),
                navigationIcon = {
                    AppIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = appText(R.string.scanner_close))
                    }
                },
                containerColor = pageColor
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(88.dp)
                            .background(colors.primaryContainer.controlContainer(), RoundedCornerShape(28.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.LocalLaundryService, contentDescription = null,
                            tint = colors.primary, modifier = Modifier.size(44.dp))
                    }
                    Text(appText(R.string.msg_2b207da009d1), modifier = Modifier.wallpaperTextBacking(), style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text(appText(R.string.msg_5124eb2d64ae), modifier = Modifier.wallpaperTextBacking(), color = colors.onSurfaceVariant)
                    Text(appText(R.string.msg_81f2e88ab5a7 , BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        modifier = Modifier.wallpaperTextBacking(), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    Text(appText(R.string.msg_b646c5129632 , BuildConfig.INTERNAL_VERSION),
                        modifier = Modifier.wallpaperTextBacking(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }

                Text(appText(R.string.msg_a5a749ecf99b),
                    modifier = Modifier.wallpaperTextBacking(), style = MaterialTheme.typography.bodyLarge)

                AppOutlinedCard(onClick = {
                    runCatching {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText(appText(R.string.msg_5c22e569d581), UsageDocuments.FEEDBACK_GROUP))
                    }.onSuccess { scope.launch { snackbar.showSnackbar(appText(R.string.msg_7e1c2cd5e619)) } }
                        .onFailure { scope.launch { snackbar.showSnackbar(appText(R.string.msg_b0e7cbd7c9aa , UsageDocuments.FEEDBACK_GROUP)) } }
                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Outlined.Groups, null, tint = colors.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(appText(R.string.msg_f4436c0e1576), style = MaterialTheme.typography.titleMedium)
                            Text(appText(R.string.msg_afdbcbccf8a9 , UsageDocuments.FEEDBACK_GROUP), style = MaterialTheme.typography.bodyMedium)
                            Text(appText(R.string.msg_abdf0bb6c80c), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Icon(Icons.Outlined.ContentCopy, appText(R.string.msg_46e651c95c83))
                    }
                }

                AppOutlinedCard(
                    onClick = {
                        runCatching { uriHandler.openUri(SOURCE_URL) }.onFailure {
                            scope.launch { snackbar.showSnackbar(appText(R.string.msg_c66b688131ed)) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Row(modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Outlined.Code, contentDescription = null, tint = colors.primary)
                        Column(modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(appText(R.string.msg_225342a2ef09), style = MaterialTheme.typography.titleMedium)
                            Text("github.com/xiyuchengxu/jianxi",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = appText(R.string.msg_bfcf57d4bef3))
                    }
                }

                AppButton(controlId = "about.support",
                    onClick = { donationOpen = true },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(appText(R.string.msg_8279f6a35954), style = MaterialTheme.typography.titleMedium)
                }

                AppOutlinedButton(
                    onClick = {
                        runCatching { uriHandler.openUri(EASTER_EGG_URL) }.onFailure {
                            scope.launch { snackbar.showSnackbar(appText(R.string.msg_e0be747a5d8c)) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Text(appText(R.string.msg_49e131043706), style = MaterialTheme.typography.titleMedium)
                }

                AppCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(appText(R.string.msg_cb4ba40bf823), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(appText(R.string.msg_f37173351372), color = colors.onSurfaceVariant)
                        AppTextButton(onClick = { documentOpen = "disclaimer" }) { Text(appText(R.string.msg_280af617c87f)) }
                        AppTextButton(onClick = { documentOpen = "rules" }) { Text(appText(R.string.msg_15d8fa4535c2)) }
                    }
                }

                AppCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(appText(R.string.msg_ef9454bff0b4), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                        Text("MIT License", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold)
                        Text(appText(R.string.msg_64328cd1a729),
                            color = colors.onSurfaceVariant)
                        AppTextButton(onClick = { licenseOpen = true }) { Text(appText(R.string.msg_64f7d3d535d8)) }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(appText(R.string.msg_788ef5156b41), style = MaterialTheme.typography.titleMedium)
                    Text(appText(R.string.msg_76057ded0624),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    AppTextButton(onClick = { colorLicenseOpen = true }) { Text(appText(R.string.msg_1b205f76c96d)) }
                }

                AppOutlinedCard(onClick = { fontLicenseOpen = true }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Outlined.TextFields, null, tint = colors.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(appText(R.string.font_license_entry), style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Default)
                            Text(appText(R.string.font_license_author), style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant, fontFamily = FontFamily.Default)
                            Text(appText(R.string.font_license_upstream), style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant, fontFamily = FontFamily.Default)
                        }
                        Icon(Icons.Outlined.ChevronRight, appText(R.string.font_license_title),
                            tint = colors.onSurfaceVariant)
                    }
                }

                Text(appText(R.string.msg_13ee5f6fac47),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }

    if (donationOpen) DonationDialog(onClose = { donationOpen = false })

    if (documentOpen.isNotBlank()) UsageDocumentDialog(
        title = if (documentOpen == "disclaimer") appText(R.string.msg_280af617c87f) else appText(R.string.msg_15d8fa4535c2),
        content = if (documentOpen == "disclaimer") documents.disclaimer else documents.rules,
        onDismiss = { documentOpen = "" }
    )

    if (colorLicenseOpen) {
        val license = remember(context) {
            context.resources.openRawResource(R.raw.material_color_utilities_license).bufferedReader().use { it.readText() }
        }
        AlertDialog(onDismissRequest = { colorLicenseOpen = false }, title = { Text("Apache License 2.0") },
            text = {
                SelectionContainer {
                    Text(license, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
                }
            }, confirmButton = { AppTextButton(onClick = { colorLicenseOpen = false }) { Text(appText(R.string.msg_c0b3fbff51cc)) } })
    }

    if (licenseOpen) {
        val license = remember(context) {
            context.resources.openRawResource(R.raw.mit_license).bufferedReader().use { it.readText() }
        }
        AlertDialog(
            onDismissRequest = { licenseOpen = false },
            title = { Text("MIT License") },
            text = {
                SelectionContainer {
                    Text(license, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = { AppTextButton(onClick = { licenseOpen = false }) { Text(appText(R.string.msg_c0b3fbff51cc)) } }
        )
    }

    if (fontLicenseOpen) {
        // The offline license text and every control stay on the system family so a decorative
        // or partially encoded app font can never make the license unreadable.
        val license = remember(context) {
            context.resources.openRawResource(R.raw.yunzhuan_duren_license).bufferedReader().use { it.readText() }
        }
        AlertDialog(
            onDismissRequest = { fontLicenseOpen = false },
            title = { Text(appText(R.string.font_license_title), fontFamily = FontFamily.Default) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(appText(R.string.font_license_author), style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Default)
                    Text(appText(R.string.font_license_upstream), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, fontFamily = FontFamily.Default)
                    Text(appText(R.string.font_license_revision), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, fontFamily = FontFamily.Default)
                    Text(appText(R.string.font_license_note), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, fontFamily = FontFamily.Default)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SelectionContainer {
                        Text(license, style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Default)
                    }
                }
            },
            confirmButton = {
                AppTextButton(onClick = { fontLicenseOpen = false }) {
                    Text(appText(R.string.msg_c0b3fbff51cc), fontFamily = FontFamily.Default)
                }
            }
        )
    }
}
