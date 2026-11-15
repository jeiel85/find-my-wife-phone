package io.github.jeiel85.findmywifephone.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.core.PairEvent
import io.github.jeiel85.findmywifephone.core.PairOffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed class PairUi {
    data object Idle : PairUi()
    data object Working : PairUi()
    data class Done(val name: String) : PairUi()
    data class Error(val code: String) : PairUi()
}

@Composable
fun PairScreen(initialOffer: PairOffer?, onOfferConsumed: () -> Unit, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<PairUi>(PairUi.Idle) }
    val scope = rememberCoroutineScope()

    fun join(offer: PairOffer?) {
        if (offer == null) { state = PairUi.Error("invalid_qr"); return }
        state = PairUi.Working
        scope.launch {
            Hub.join(offer)
                .onSuccess { peer ->
                    // The relay already linked us; hello-ack only adds the peer's device name.
                    delay(12_000)
                    if (state == PairUi.Working) state = PairUi.Done(peer.name)
                }
                .onFailure { state = PairUi.Error(it.message ?: "error") }
        }
    }

    LaunchedEffect(initialOffer) {
        if (initialOffer != null) { tab = 1; join(initialOffer); onOfferConsumed() }
    }
    LaunchedEffect(Unit) {
        Hub.pairEvents.collect { e ->
            state = when (e) {
                is PairEvent.Paired -> PairUi.Done(e.peerName)
                is PairEvent.Failed -> PairUi.Error(e.code)
            }
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { join(PairOffer.parse(it)) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = P.c.ink) }
            Text("가족 폰 연결", style = MaterialTheme.typography.headlineSmall, color = P.c.ink)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(P.c.surfaceSoft).padding(4.dp)) {
            listOf("내 QR 보여주기" to Icons.Rounded.QrCode2, "QR 스캔하기" to Icons.Rounded.QrCodeScanner).forEachIndexed { i, (label, icon) ->
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(50))
                        .background(if (tab == i) P.c.surface else Color.Transparent)
                        .clickable { tab = i; if (state !is PairUi.Done) state = PairUi.Idle }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, tint = if (tab == i) P.c.coral else P.c.inkSoft, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (tab == i) P.c.ink else P.c.inkSoft)
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        when (val s = state) {
            is PairUi.Done -> DoneCard(s.name, onBack)
            else -> if (tab == 0) ShowQr() else ScanPanel(s, onScan = {
                scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                    .setPrompt("상대 폰의 QR을 비춰 주세요").setOrientationLocked(false))
            })
        }
    }
}

@Composable
private fun ShowQr() {
    var offer by remember { mutableStateOf<PairOffer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(generation) {
        offer = null; error = null
        Hub.createOffer().onSuccess { offer = it }.onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val bitmap = remember(offer) { offer?.let { qr(it.toLink()) } }

    Card(Modifier.fillMaxWidth(), padding = 24.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("상대 폰에서 이 앱을 열고\n'QR 스캔하기'로 비춰 주세요", textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium, color = P.c.ink)
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth(0.82f).aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color.White),
                contentAlignment = Alignment.Center) {
                when {
                    bitmap != null -> Image(bitmap.asImageBitmap(), "연결용 QR 코드", Modifier.fillMaxSize().padding(14.dp),
                        filterQuality = FilterQuality.None)
                    error != null -> Text(errorText(error!!), color = P.c.danger, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                    else -> CircularProgressIndicator(color = P.c.coral)
                }
            }
            Spacer(Modifier.height(16.dp))
            val left = offer?.let { ((it.expiresAt - now) / 1000).coerceAtLeast(0) }
            if (left != null && left > 0) {
                Text("%d:%02d 동안 유효 · 한 번만 쓸 수 있어요".format(left / 60, left % 60), color = P.c.inkSoft, fontSize = 13.sp)
            } else if (offer != null || error != null) {
                PillButton("새 QR 만들기", Icons.Rounded.Refresh, P.c.coral, P.c.onCoral) { generation++ }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Text("QR에는 이 폰의 공개 키와 한 번 쓰는 비밀값이 들어 있어요. 중계 서버는 이 비밀값을 모르기 때문에 두 폰 사이의 메시지를 읽을 수 없어요.",
        style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft, modifier = Modifier.padding(horizontal = 6.dp))
}

@Composable
private fun ScanPanel(state: PairUi, onScan: () -> Unit) {
    Card(Modifier.fillMaxWidth(), padding = 24.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.QrCodeScanner, null, tint = P.c.coral, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("상대 폰에서 '내 QR 보여주기'를 연 뒤\n아래 버튼으로 스캔해 주세요", textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium, color = P.c.ink)
            Spacer(Modifier.height(20.dp))
            when (state) {
                PairUi.Working -> {
                    CircularProgressIndicator(color = P.c.coral)
                    Spacer(Modifier.height(10.dp))
                    Text("연결하는 중…", color = P.c.inkSoft)
                }
                else -> {
                    if (state is PairUi.Error) {
                        Text(if (state.code == "invalid_qr") "이 앱의 연결용 QR이 아니에요" else errorText(state.code),
                            color = P.c.danger, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                    }
                    PillButton("스캔 시작", Icons.Rounded.QrCodeScanner, P.c.coral, P.c.onCoral, Modifier.fillMaxWidth(), onClick = onScan)
                }
            }
        }
    }
}

@Composable
private fun DoneCard(name: String, onBack: () -> Unit) {
    Card(Modifier.fillMaxWidth(), padding = 28.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.CheckCircle, null, tint = P.c.sage, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(12.dp))
            Text("${name}님과 연결됐어요", style = MaterialTheme.typography.headlineSmall, color = P.c.ink)
            Spacer(Modifier.height(6.dp))
            Text("이제 서로의 폰을 울리고 찾을 수 있어요.", color = P.c.inkSoft)
            Spacer(Modifier.height(24.dp))
            PillButton("확인", null, P.c.coral, P.c.onCoral, Modifier.fillMaxWidth(), onClick = onBack)
        }
    }
}

private fun qr(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) 0xFF1F2235.toInt() else 0xFFFFFFFF.toInt())
    return bmp
}
