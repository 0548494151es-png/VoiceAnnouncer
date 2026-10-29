package com.voiceannouncer.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Purple = Color(0xFF6A1B9A)
private val Pink = Color(0xFFE91E63)
private val Orange = Color(0xFFFF9800)
private val Teal = Color(0xFF00BFA5)
private val Blue = Color(0xFF2979FF)
private val Green = Color(0xFF00C853)
private val Bg = Color(0xFFFFF3FB)

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Speaker.init(this)
        if (Prefs(this).enabled) startAnnouncer(this)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Screen(resumeTick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, MessageListener::class.java)
            )
        } catch (_: Exception) {
        }
    }
}

private fun has(ctx: Context, perm: String) =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

@Composable
private fun Screen(tick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(prefs.enabled) }
    var calls by remember { mutableStateOf(prefs.calls) }
    var repeatCount by remember { mutableIntStateOf(prefs.repeatCount) }
    var repeatForever by remember { mutableStateOf(prefs.repeatForever) }
    var gapSec by remember { mutableIntStateOf(prefs.repeatGapSec) }
    var callRate by remember { mutableStateOf(prefs.callRate) }
    var muteRing by remember { mutableStateOf(prefs.muteRing) }
    var tplKnown by remember { mutableStateOf(prefs.tplKnown) }
    var tplUnknown by remember { mutableStateOf(prefs.tplUnknown) }
    var tplPrivate by remember { mutableStateOf(prefs.tplPrivate) }
    var tplMessage by remember { mutableStateOf(prefs.tplMessage) }
    var whatsapp by remember { mutableStateOf(prefs.whatsapp) }
    var sms by remember { mutableStateOf(prefs.sms) }
    var readBody by remember { mutableStateOf(prefs.readBody) }
    var btOnly by remember { mutableStateOf(prefs.btOnly) }
    var skipSilent by remember { mutableStateOf(prefs.skipSilent) }
    var pitch by remember { mutableStateOf(prefs.pitch) }
    var rate by remember { mutableStateOf(prefs.rate) }
    var voice by remember { mutableStateOf(prefs.voice) }
    var repo by remember { mutableStateOf(prefs.repo) }
    var token by remember { mutableStateOf(prefs.token) }

    // ---- הרשאות ----
    val perms = remember {
        buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    var permTick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permTick++ }

    val basicOk = remember(tick, permTick) { perms.all { has(ctx, it) } }
    val listenerOk = remember(tick, permTick) {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
    val dndOk = remember(tick, permTick) { Speaker.canMuteRing(ctx) }
    val batteryOk = remember(tick, permTick) {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }

    // ---- קולות ----
    var voices by remember { mutableStateOf(Speaker.hebrewVoices()) }
    LaunchedEffect(Unit) {
        repeat(6) {
            delay(1000)
            voices = Speaker.hebrewVoices()
        }
    }
    var voiceMenu by remember { mutableStateOf(false) }

    // ---- עדכון ----
    var status by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<Updater.Info?>(null) }
    var progress by remember { mutableStateOf(-1f) }
    var busy by remember { mutableStateOf(false) }
    val current = remember { Updater.currentVersion(ctx) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
    ) {
        // ---- כותרת ----
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(Purple, Pink, Orange)))
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Text("🔊 קורא הודעות", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text("מקריא בקול שיחות והודעות נכנסות", fontSize = 15.sp, color = Color(0xFFFFE9F3))
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.22f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (enabled) "פעיל ✅" else "כבוי ⛔",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        prefs.enabled = it
                        if (it) startAnnouncer(ctx)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Green,
                        uncheckedTrackColor = Color(0xFFB0B0B0)
                    )
                )
            }
        }

        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- הרשאות ----
            SectionCard("הרשאות", "🔑", Orange) {
                PermRow("טלפון, יומן שיחות ואנשי קשר" + if (Build.VERSION.SDK_INT >= 33) " והתראות" else "", basicOk, "אשר") {
                    launcher.launch(perms)
                }
                PermRow("גישה להתראות (לקריאת הודעות)", listenerOk, "פתח") {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                PermRow("עבודה ברקע בלי הגבלת סוללה", batteryOk, "אשר") {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }
                if (muteRing) {
                    PermRow("השתקת הצלצול בזמן ההכרזה ('נא לא להפריע')", dndOk, "אשר") {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }
                }
                if (!listenerOk) {
                    Text(
                        "אם האפשרות 'גישה להתראות' אפורה: הגדרות ← אפליקציות ← קורא הודעות ← שלוש נקודות ← 'אפשר הגדרות מוגבלות'.",
                        fontSize = 12.sp, color = Color.Gray
                    )
                }
            }

            // ---- מה להקריא ----
            SectionCard("מה להקריא", "📣", Pink) {
                ToggleRow("שיחות נכנסות", "מקריא את שם המתקשר (הגדרות מפורטות בכרטיס 'הכרזת שיחות')", calls, Pink) { calls = it; prefs.calls = it }
                ToggleRow("וואטסאפ", null, whatsapp, Green) { whatsapp = it; prefs.whatsapp = it }
                ToggleRow("הודעות SMS", null, sms, Blue) { sms = it; prefs.sms = it }
                ToggleRow("להקריא גם את תוכן ההודעה", "כבוי = רק שם השולח", readBody, Purple) { readBody = it; prefs.readBody = it }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = tplMessage,
                    onValueChange = { tplMessage = it; prefs.tplMessage = it },
                    label = { Text("נוסח הקראת הודעה") },
                    supportingText = { Text("{name} = שם השולח, {text} = תוכן ההודעה") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- הכרזת שיחות ----
            SectionCard("הכרזת שיחות", "📞", Teal) {
                ToggleRow(
                    "להשתיק את הצלצול בזמן ההכרזה",
                    "הצלצול נחתך רק בזמן שהשם מוקרא, ואחרי זה ממשיך",
                    muteRing, Teal
                ) { muteRing = it; prefs.muteRing = it }

                Spacer(Modifier.height(4.dp))
                Text(
                    if (repeatForever) "כמה פעמים להכריז: עד שעונים"
                    else "כמה פעמים להכריז: $repeatCount",
                    fontSize = 14.sp
                )
                Slider(
                    value = repeatCount.toFloat(),
                    onValueChange = { repeatCount = it.roundToInt(); prefs.repeatCount = repeatCount },
                    valueRange = 1f..10f,
                    steps = 8,
                    enabled = !repeatForever,
                    colors = SliderDefaults.colors(thumbColor = Teal, activeTrackColor = Teal)
                )
                ToggleRow("לחזור עד שעונים", "מתעלם ממספר הפעמים", repeatForever, Teal) {
                    repeatForever = it; prefs.repeatForever = it
                }

                Text("מרווח בין הכרזות: $gapSec שניות", fontSize = 14.sp)
                Slider(
                    value = gapSec.toFloat(),
                    onValueChange = { gapSec = it.roundToInt(); prefs.repeatGapSec = gapSec },
                    valueRange = 1f..30f,
                    steps = 28,
                    colors = SliderDefaults.colors(thumbColor = Pink, activeTrackColor = Pink)
                )

                Text("מהירות הכרזה: ${"%.2f".format(callRate)}", fontSize = 14.sp)
                Slider(
                    value = callRate, onValueChange = { callRate = it; prefs.callRate = it },
                    valueRange = 0.6f..1.6f,
                    colors = SliderDefaults.colors(thumbColor = Orange, activeTrackColor = Orange)
                )

                Spacer(Modifier.height(8.dp))
                Text("מה להגיד", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = tplKnown,
                    onValueChange = { tplKnown = it; prefs.tplKnown = it },
                    label = { Text("מתקשר מאנשי הקשר") },
                    supportingText = { Text("{name} = שם המתקשר. למשל: {name} מתקשר") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = tplUnknown,
                    onValueChange = { tplUnknown = it; prefs.tplUnknown = it },
                    label = { Text("מספר לא שמור") },
                    supportingText = { Text("{number} = המספר") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = tplPrivate,
                    onValueChange = { tplPrivate = it; prefs.tplPrivate = it },
                    label = { Text("מספר חסוי") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = {
                    prefs.resetTemplates()
                    tplKnown = prefs.tplKnown
                    tplUnknown = prefs.tplUnknown
                    tplPrivate = prefs.tplPrivate
                    tplMessage = prefs.tplMessage
                }) { Text("↺ איפוס הנוסחים לברירת מחדל") }

                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        Speaker.startRinging(
                            ctx,
                            fillTemplate(prefs.tplKnown, name = "דוד כהן", number = "0 5 0 1 2 3 4 5 6 7"),
                            allowMute = false
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Teal),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("▶ נסה הכרזת שיחה") }
                TextButton(
                    onClick = { Speaker.stopRinging() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("⏹ עצור") }
            }

            // ---- תנאים ----
            SectionCard("מתי להקריא", "🎧", Blue) {
                ToggleRow("רק כשמחובר בלוטוס", "אוזניות / רכב / רמקול", btOnly, Blue) { btOnly = it; prefs.btOnly = it }
                ToggleRow("לא להקריא בשקט / רטט", null, skipSilent, Blue) { skipSilent = it; prefs.skipSilent = it }
            }

            // ---- קול ----
            SectionCard("הקול", "🎙️", Purple) {
                Text("גובה קול (נמוך = גברי יותר): ${"%.2f".format(pitch)}", fontSize = 14.sp)
                Slider(
                    value = pitch, onValueChange = { pitch = it; prefs.pitch = it },
                    valueRange = 0.5f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple)
                )
                Text("מהירות הקראת הודעות: ${"%.2f".format(rate)}", fontSize = 14.sp)
                Slider(
                    value = rate, onValueChange = { rate = it; prefs.rate = it },
                    valueRange = 0.6f..1.6f,
                    colors = SliderDefaults.colors(thumbColor = Pink, activeTrackColor = Pink)
                )
                Box {
                    Button(
                        onClick = { voiceMenu = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Purple),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val label = if (voice.isBlank()) "קול: אוטומטי" else "קול: $voice"
                        Text(label)
                    }
                    DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("אוטומטי") },
                            onClick = { voice = ""; prefs.voice = ""; voiceMenu = false }
                        )
                        voices.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(v.name) },
                                onClick = { voice = v.name; prefs.voice = v.name; voiceMenu = false }
                            )
                        }
                    }
                }
                if (voices.isEmpty()) {
                    Text(
                        "לא נמצאו קולות בעברית. התקן 'Speech Services by Google' והורד קול עברי.",
                        fontSize = 12.sp, color = Color.Gray
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { Speaker.speak(ctx, "בדיקה. כך נשמע קורא ההודעות") },
                    colors = ButtonDefaults.buttonColors(containerColor = Pink),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("▶ נסה את הקול") }
            }

            // ---- עדכון ----
            SectionCard("עדכון האפליקציה", "⬆️", Green) {
                Text("גרסה נוכחית: ${current.second}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            info = null
                            status = "בודק עדכונים..."
                            val r = withContext(Dispatchers.IO) { runCatching { Updater.check(ctx) } }
                            r.onSuccess {
                                info = it
                                status = if (it == null) "האפליקציה מעודכנת ✅" else "יש גרסה חדשה: ${it.tag} 🎉"
                            }.onFailure { status = "שגיאה: ${it.message}" }
                            busy = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("🔍 בדוק עדכון") }

                val newer = info
                if (newer != null) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                progress = 0f
                                status = "מוריד..."
                                val r = withContext(Dispatchers.IO) {
                                    runCatching { Updater.download(ctx, newer) { progress = it } }
                                }
                                r.onSuccess { f ->
                                    val started = Updater.install(ctx, f)
                                    status = if (started) "אשר את ההתקנה בחלון שנפתח" else
                                        "אפשר 'התקנה מהאפליקציה הזו' ואז לחץ שוב על 'הורד והתקן'"
                                }.onFailure { status = "שגיאה: ${it.message}" }
                                progress = -1f
                                busy = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Orange),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("⬇ הורד והתקן ${newer.tag}") }
                }
                if (progress >= 0f) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, color = Green, modifier = Modifier.fillMaxWidth())
                }
                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(status, fontSize = 14.sp)
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it; prefs.repo = it },
                    label = { Text("מאגר GitHub (משתמש/מאגר)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; prefs.token = it },
                    label = { Text("טוקן GitHub (רק אם המאגר פרטי)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: String,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) { Text(icon, fontSize = 20.sp) }
                Spacer(Modifier.width(12.dp))
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    sub: String?,
    checked: Boolean,
    accent: Color,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Color.Gray)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent)
        )
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (ok) "✅" else "⚠️", fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (!ok) {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = Orange)
            ) { Text(action) }
        }
    }
}
