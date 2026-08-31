package com.lazy.smsrelay.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lazy.smsrelay.core.Engine
import com.lazy.smsrelay.data.ChannelConfig
import com.lazy.smsrelay.data.ChannelType
import com.lazy.smsrelay.data.Db
import com.lazy.smsrelay.data.LogItem
import com.lazy.smsrelay.data.Prefs
import com.lazy.smsrelay.data.RelayRule
import com.lazy.smsrelay.data.SmsEvent
import com.lazy.smsrelay.net.Sender
import com.lazy.smsrelay.service.RelayService
import com.lazy.smsrelay.util.Guards
import com.lazy.smsrelay.util.GuardItem
import com.lazy.smsrelay.util.GuardState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1F7A5A),
            onPrimary = Color.White,
            secondary = Color(0xFF00695C)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(ctx: Context, onRequestSmsPermission: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var guardRefresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tabs = listOf("保活", "规则", "通道", "日志")
    val icons = listOf(Icons.Default.Shield, Icons.Default.Sms, Icons.Default.Send, Icons.Default.List)

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("SmsRelay") },
                actions = {
                    IconButton(onClick = { guardRefresh++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新自检")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icons[i], contentDescription = t) },
                        label = { Text(t) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> GuardScreen(ctx, guardRefresh, onRequestSmsPermission) { message = it }
                1 -> RulesScreen(ctx) { message = it }
                2 -> ChannelsScreen(ctx, scope) { message = it }
                3 -> LogsScreen(ctx) { message = it }
            }
        }
    }
}

/* ============================== 保活自检 ============================== */

@Composable
private fun GuardScreen(
    ctx: Context,
    refreshKey: Int,
    onRequestSmsPermission: () -> Unit,
    setMessage: (String) -> Unit
) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshKey) { tick++ }
    val guards = remember(tick) { Guards.build(ctx) }
    val (done, total) = remember(tick) { Guards.requiredDone(ctx) }
    var serviceOn by remember(tick) { mutableStateOf(Prefs.serviceEnabled(ctx)) }
    var notifyBackup by remember(tick) { mutableStateOf(Prefs.notifyBackupEnabled(ctx)) }
    var showConsent by remember { mutableStateOf(!Prefs.userConsent(ctx)) }
    var manualFor by remember { mutableStateOf<GuardItem?>(null) }
    val scope = rememberCoroutineScope()

    if (showConsent) {
        ConsentDialog(
            onAgree = {
                Prefs.markConsent(ctx, true)
                showConsent = false
                tick++
            },
            onDisagree = { setMessage("未同意前不会转发任何短信") }
        )
    }

    manualFor?.let { item ->
        AlertDialog(
            onDismissRequest = { manualFor = null },
            title = { Text(item.title) },
            text = { Text(item.manualPath, fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { manualFor = null }) { Text("知道了") } }
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "必需项完成 $done / $total",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (done == total) Color(0xFF1B7A3E) else Color(0xFFC62828)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "澎湃OS 4（基于 Android 17）对后台的限制比上一代更狠。下面标「关键」的几项不开，转发一定不稳。",
                        fontSize = 13.sp,
                        color = Color(0xFF666666)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("转发总开关", Modifier.weight(1f))
                        Switch(checked = serviceOn, onCheckedChange = {
                            serviceOn = it
                            Prefs.updateServiceEnabled(ctx, it)
                            if (it) RelayService.tryStart(ctx) else RelayService.stop(ctx)
                            tick++
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("通知兜底通道", Modifier.weight(1f))
                        Switch(checked = notifyBackup, onCheckedChange = {
                            notifyBackup = it
                            Prefs.setNotifyBackup(ctx, it)
                        })
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onRequestSmsPermission() },
                    modifier = Modifier.weight(1f)
                ) { Text("申请权限") }
                OutlinedButton(
                    onClick = { tick++ },
                    modifier = Modifier.weight(1f)
                ) { Text("重新检查") }
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val ev = SmsEvent(
                            from = "10086",
                            body = "【测试】您的验证码是 123456，5 分钟内有效",
                            simSlot = 0,
                            source = "test"
                        )
                        withContext(Dispatchers.IO) {
                            Engine.onSms(ctx, ev)
                            Engine.flush(ctx, limit = 5)
                        }
                        setMessage("已注入一条测试短信，请查看日志页")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("注入测试短信（不走真实短信，验证规则与通道）") }
        }

        items(guards) { g ->
            GuardCard(
                g,
                onOpen = {
                    val jumped = runCatching { Guards.open(ctx, g.key) }.getOrDefault(false)
                    if (!jumped) manualFor = g
                    else setMessage("已尝试跳转，若无反应请按手动路径操作")
                },
                onManual = { manualFor = g }
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun GuardCard(g: GuardItem, onOpen: () -> Unit, onManual: () -> Unit) {
    val (chipText, chipColor) = when (g.state) {
        GuardState.OK -> "已完成" to Color(0xFF1B7A3E)
        GuardState.TODO -> "待处理" to Color(0xFFC62828)
        GuardState.UNKNOWN -> "待确认" to Color(0xFFB26A00)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    g.title,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                AssistChip(
                    onClick = {},
                    label = { Text(chipText, fontSize = 12.sp) },
                    colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                        labelColor = chipColor
                    )
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(g.desc, fontSize = 13.sp, color = Color(0xFF555555))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen) {
                    Text(if (g.jumpable) "去设置" else "打开应用信息")
                }
                OutlinedButton(onClick = onManual) { Text("手动路径") }
            }
        }
    }
}

@Composable
private fun ConsentDialog(onAgree: () -> Unit, onDisagree: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDisagree,
        title = { Text("使用前须知") },
        text = {
            Text(
                "本应用会把收到的短信内容转发到你指定的通道（另一台手机、机器人或自建服务）。\n\n" +
                    "请确认：\n" +
                    "· 该设备上的短信由你本人接收和处置；\n" +
                    "· 若转发他人短信，需事先取得对方同意；\n" +
                    "· 转发链路上的第三方服务（Telegram、企业微信、自建 Webhook 等）由你自行负责其安全性。\n\n" +
                    "短信内容默认只在本机短暂留存，可随时在「日志」页清空。",
                fontSize = 14.sp
            )
        },
        confirmButton = { Button(onClick = onAgree) { Text("我已了解并同意") } },
        dismissButton = { TextButton(onClick = onDisagree) { Text("暂不同意") } }
    )
}

/* ================================ 规则 ================================ */

@Composable
private fun RulesScreen(ctx: Context, setMessage: (String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var rules by remember(tick) { mutableStateOf(Prefs.loadRules(ctx)) }
    var editing by remember { mutableStateOf<RelayRule?>(null) }
    var isNew by remember { mutableStateOf(false) }

    editing?.let { rule ->
        RuleDialog(
            rule = rule,
            ctx = ctx,
            isNew = isNew,
            onDismiss = { editing = null },
            onSave = {
                val list = Prefs.loadRules(ctx)
                if (isNew) list.add(it) else {
                    val idx = list.indexOfFirst { r -> r.id == it.id }
                    if (idx >= 0) list[idx] = it
                }
                Prefs.saveRules(ctx, list)
                editing = null
                tick++
                setMessage("已保存")
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    "规则决定「什么样的短信要转发」。多条规则之间是「或」的关系，" +
                        "同一条短信命中多条规则时会分别转发到各自绑定的通道。",
                    fontSize = 13.sp, color = Color(0xFF666666)
                )
            }
            items(rules, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Switch(checked = r.enabled, onCheckedChange = { v ->
                                val list = Prefs.loadRules(ctx)
                                val i = list.indexOfFirst { it.id == r.id }
                                if (i >= 0) list[i] = r.copy(enabled = v)
                                Prefs.saveRules(ctx, list)
                                tick++
                            })
                            IconButton(onClick = {
                                val list = Prefs.loadRules(ctx).filter { it.id != r.id }
                                Prefs.saveRules(ctx, list)
                                tick++
                            }) { Icon(Icons.Default.Delete, contentDescription = "删除") }
                        }
                        Text(
                            buildString {
                                if (r.senderFilter.isNotBlank()) append("号码：${r.senderFilter}  ")
                                if (r.keyword.isNotBlank()) append("关键词：${r.keyword}  ")
                                if (r.regex.isNotBlank()) append("正则：${r.regex}  ")
                                if (r.simSlot >= 0) append("仅 SIM${r.simSlot + 1}  ")
                                if (r.onlyOtp) append("仅验证码  ")
                                append(if (r.channelIds.isEmpty()) "通道：全部" else "通道：${r.channelIds.size} 个")
                            },
                            fontSize = 12.sp, color = Color(0xFF777777)
                        )
                        TextButton(onClick = { editing = r; isNew = false }) { Text("编辑") }
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }

        androidx.compose.material3.FloatingActionButton(
            onClick = {
                editing = RelayRule(id = "r" + System.currentTimeMillis())
                isNew = true
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.Add, contentDescription = "新增规则") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleDialog(
    rule: RelayRule,
    ctx: Context,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (RelayRule) -> Unit
) {
    var name by remember { mutableStateOf(rule.name) }
    var sender by remember { mutableStateOf(rule.senderFilter) }
    var keyword by remember { mutableStateOf(rule.keyword) }
    var regex by remember { mutableStateOf(rule.regex) }
    var slot by remember { mutableIntStateOf(rule.simSlot) }
    var onlyOtp by remember { mutableStateOf(rule.onlyOtp) }
    var channels by remember { mutableStateOf(Prefs.loadChannels(ctx)) }
    var picked by remember { mutableStateOf(rule.channelIds.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增规则" else "编辑规则") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("规则名") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(sender, { sender = it }, label = { Text("发送号码包含（逗号分隔，可留空）") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(keyword, { keyword = it }, label = { Text("正文关键词（逗号分隔，可留空）") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(regex, { regex = it }, label = { Text("正文正则（可选）") })
                Spacer(Modifier.height(10.dp))
                Text("卡槽限制", fontSize = 13.sp)
                Row {
                    listOf(-1 to "不限", 0 to "SIM1", 1 to "SIM2").forEach { (v, t) ->
                        FilterChip(
                            selected = slot == v,
                            onClick = { slot = v },
                            label = { Text(t, fontSize = 12.sp) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = onlyOtp, onCheckedChange = { onlyOtp = it })
                    Text("仅转发含验证码的短信", fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("转发到（不勾选 = 全部已启用通道）", fontSize = 13.sp)
                channels.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = picked.contains(c.id),
                            onCheckedChange = {
                                picked = if (it) picked + c.id else picked - c.id
                            }
                        )
                        Text("${c.name}（${c.type.label}）", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    rule.copy(
                        name = name.ifBlank { "未命名规则" },
                        senderFilter = sender,
                        keyword = keyword,
                        regex = regex,
                        simSlot = slot,
                        onlyOtp = onlyOtp,
                        channelIds = picked.toList()
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/* ================================ 通道 ================================ */

@Composable
private fun ChannelsScreen(
    ctx: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    setMessage: (String) -> Unit
) {
    var tick by remember { mutableIntStateOf(0) }
    var channels by remember(tick) { mutableStateOf(Prefs.loadChannels(ctx)) }
    var editing by remember { mutableStateOf<ChannelConfig?>(null) }
    var isNew by remember { mutableStateOf(false) }

    editing?.let { ch ->
        ChannelDialog(
            ch = ch,
            isNew = isNew,
            onDismiss = { editing = null },
            onSave = {
                val list = Prefs.loadChannels(ctx)
                if (isNew) list.add(it) else {
                    val i = list.indexOfFirst { c -> c.id == it.id }
                    if (i >= 0) list[i] = it
                }
                Prefs.saveChannels(ctx, list)
                editing = null
                tick++
                setMessage("已保存")
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    "通道决定「转发到哪里」。建议至少配一个 HTTP 类通道 + 一个短信回发通道做互备。",
                    fontSize = 13.sp, color = Color(0xFF666666)
                )
            }
            items(channels, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name.ifBlank { c.type.label }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Switch(checked = c.enabled, onCheckedChange = { v ->
                                val list = Prefs.loadChannels(ctx)
                                val i = list.indexOfFirst { it.id == c.id }
                                if (i >= 0) list[i] = c.copy(enabled = v)
                                Prefs.saveChannels(ctx, list)
                                tick++
                            })
                            IconButton(onClick = {
                                Prefs.saveChannels(ctx, Prefs.loadChannels(ctx).filter { it.id != c.id })
                                tick++
                            }) { Icon(Icons.Default.Delete, contentDescription = "删除") }
                        }
                        Text(c.type.label, fontSize = 12.sp, color = Color(0xFF777777))
                        Spacer(Modifier.height(6.dp))
                        Row {
                            TextButton(onClick = { editing = c; isNew = false }) { Text("编辑") }
                            TextButton(onClick = {
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        runCatching {
                                            Sender.test(ctx, c, "【测试】SmsRelay 通道连通性测试")
                                        }
                                    }
                                    setMessage(
                                        if (r.isSuccess) "测试发送成功"
                                        else "测试失败：${r.exceptionOrNull()?.message}"
                                    )
                                }
                            }) { Text("测试") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }

        androidx.compose.material3.FloatingActionButton(
            onClick = {
                editing = ChannelConfig(id = "c" + System.currentTimeMillis(), name = "新通道")
                isNew = true
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.Add, contentDescription = "新增通道") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelDialog(
    ch: ChannelConfig,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ChannelConfig) -> Unit
) {
    var name by remember { mutableStateOf(ch.name) }
    var type by remember { mutableStateOf(ch.type) }
    var url by remember { mutableStateOf(ch.url) }
    var token by remember { mutableStateOf(ch.token) }
    var secret by remember { mutableStateOf(ch.secret) }
    var target by remember { mutableStateOf(ch.target) }
    var slot by remember { mutableIntStateOf(ch.simSlot) }
    var template by remember { mutableStateOf(ch.template) }
    var expanded by remember { mutableStateOf(false) }

    val hint = when (type) {
        ChannelType.WEBHOOK -> "接收 JSON 的完整地址，建议 HTTPS"
        ChannelType.BARK -> "device_key（URL 留空则用官方 api.day.app）"
        ChannelType.SERVERCHAN -> "SendKey（形如 SCTxxxxx）"
        ChannelType.TELEGRAM -> "Bot Token，如 123456:ABC-DEF..."
        ChannelType.WECOM -> "机器人 Webhook 地址（含 key 参数）"
        ChannelType.DINGTALK -> "机器人 Webhook 地址（含 access_token）"
        ChannelType.FEISHU -> "机器人 Webhook 地址"
        ChannelType.SMS_OUT -> "目标手机号"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增通道" else "编辑通道") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text(type.label) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        ChannelType.values().forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.label) },
                                onClick = { type = t; expanded = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (type != ChannelType.SMS_OUT) {
                    OutlinedTextField(url, { url = it }, label = { Text("地址 URL") })
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(token, { token = it }, label = { Text("Token / Key") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                if (type == ChannelType.DINGTALK || type == ChannelType.FEISHU ||
                    type == ChannelType.WEBHOOK
                ) {
                    OutlinedTextField(secret, { secret = it }, label = { Text("签名密钥（可选）") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                }
                if (type == ChannelType.TELEGRAM || type == ChannelType.SMS_OUT) {
                    OutlinedTextField(
                        target, { target = it },
                        label = { Text(if (type == ChannelType.TELEGRAM) "chat_id" else "目标手机号") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (type == ChannelType.SMS_OUT) KeyboardType.Phone else KeyboardType.Text
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (type == ChannelType.SMS_OUT) {
                    Text("回发使用的 SIM", fontSize = 13.sp)
                    Row {
                        listOf(-1 to "默认卡", 0 to "SIM1", 1 to "SIM2").forEach { (v, t) ->
                            FilterChip(
                                selected = slot == v,
                                onClick = { slot = v },
                                label = { Text(t, fontSize = 12.sp) },
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    template, { template = it },
                    label = { Text("本通道模板（留空用全局模板）") },
                    minLines = 2
                )
                Spacer(Modifier.height(6.dp))
                Text("提示：$hint", fontSize = 12.sp, color = Color(0xFF777777))
                Text(
                    "可用变量：{from} {body} {time} {sim} {otp} {device} {rule}",
                    fontSize = 12.sp, color = Color(0xFF777777)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    ch.copy(
                        name = name.ifBlank { type.label },
                        type = type,
                        url = url.trim(),
                        token = token.trim(),
                        secret = secret.trim(),
                        target = target.trim(),
                        simSlot = slot,
                        template = template
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/* ================================ 日志 ================================ */

@Composable
private fun LogsScreen(ctx: Context, setMessage: (String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var logs by remember(tick) { mutableStateOf(Db.get(ctx).recentLogs(200)) }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { tick++ }, modifier = Modifier.weight(1f)) { Text("刷新") }
                OutlinedButton(
                    onClick = { Db.get(ctx).clearLogs(); tick++ },
                    modifier = Modifier.weight(1f)
                ) { Text("清空日志") }
            }
        }
        items(logs, key = { it.id }) { l ->
            LogRow(l)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LogRow(l: LogItem) {
    val color = when {
        l.status.contains("失败") -> Color(0xFFC62828)
        l.status.contains("未") -> Color(0xFF9E9E9E)
        else -> Color(0xFF1B7A3E)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.sender.ifBlank { "(未知号码)" }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(l.status, fontSize = 12.sp, color = color)
            }
            Spacer(Modifier.height(4.dp))
            Text(l.body, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "${DateFormat.format("MM-dd HH:mm:ss", l.ts)} · 来源 ${l.source}" +
                    if (l.detail.isNotBlank()) " · ${l.detail}" else "",
                fontSize = 11.sp, color = Color(0xFF888888)
            )
        }
    }
}
