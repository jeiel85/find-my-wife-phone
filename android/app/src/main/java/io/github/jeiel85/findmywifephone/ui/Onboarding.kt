package io.github.jeiel85.findmywifephone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.jeiel85.findmywifephone.core.Hub

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(Hub.store.settings.value.myName) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(Modifier.padding(top = 8.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(2) { i ->
                Box(Modifier.height(5.dp).size(if (i == step) 28.dp else 12.dp, 5.dp).clip(RoundedCornerShape(50))
                    .background(if (i <= step) P.c.coral else P.c.hairline))
            }
        }
        if (step == 0) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(P.c.coral.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.NotificationsActive, null, tint = P.c.coral, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("폰이 안 보일 때,\n버튼 하나로 울려요", style = MaterialTheme.typography.displaySmall, color = P.c.ink)
            Spacer(Modifier.height(12.dp))
            Text("무음이나 방해 금지 모드여도 울리고, 찾으면 원래 설정으로 돌아가요. 울리기 전 10초 동안은 언제든 거절할 수 있어요.",
                style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
            Spacer(Modifier.height(36.dp))
            Text("가족에게 이 폰을 뭐라고 보여 줄까요?", style = MaterialTheme.typography.titleMedium, color = P.c.ink)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(20) },
                placeholder = { Text("예: 아내, 남편, 엄마") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = P.c.coral, unfocusedBorderColor = P.c.hairline,
                    focusedContainerColor = P.c.surface, unfocusedContainerColor = P.c.surface),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(32.dp))
            PillButton("다음", null, P.c.coral, P.c.onCoral, Modifier.fillMaxWidth(), enabled = name.isNotBlank()) {
                Hub.store.updateSettings { it.copy(myName = name.trim()) }
                step = 1
            }
        } else {
            Text("몇 가지만 허용해 주세요", style = MaterialTheme.typography.headlineMedium, color = P.c.ink)
            Spacer(Modifier.height(8.dp))
            Text("요청이 왔을 때 확실히 울리고, 누가 무엇을 요청했는지 바로 보이게 하려는 거예요. 나중에 설정에서도 바꿀 수 있어요.",
                style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth(), padding = 12.dp) {
                PermissionList(Hub.store.settings.value.locationPolicy)
            }
            Spacer(Modifier.height(28.dp))
            PillButton("시작하기", null, P.c.coral, P.c.onCoral, Modifier.fillMaxWidth()) {
                Hub.store.updateSettings { it.copy(onboarded = true) }
                onDone()
            }
            Text("이전", Modifier.fillMaxWidth().clickable { step = 0 }.padding(16.dp), color = P.c.inkSoft,
                textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
        }
    }
}
