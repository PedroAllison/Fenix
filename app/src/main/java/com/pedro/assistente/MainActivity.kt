package com.pedro.assistente

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class Msg(val text: String, val mine: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun App() {
        val ctx: Context = this
        val prefs = remember { getSharedPreferences("cfg", MODE_PRIVATE) }
        var apiKey by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
        var showCfg by remember { mutableStateOf(apiKey.isBlank()) }
        var input by remember { mutableStateOf("") }
        val msgs = remember { mutableStateListOf(Msg("Olá! Posso abrir apps/jogos, marcar no calendário e preparar mensagens.", false)) }
        val list = rememberLazyListState()
        val scope = rememberCoroutineScope()

        fun submit(text: String) {
            if (text.isBlank()) return
            msgs += Msg(text, true); input = ""
            scope.launch {
                val cmd = withContext(Dispatchers.IO) { Brain.interpret(text, apiKey) }
                msgs += Msg(Actions.run(ctx, cmd), false)
                list.animateScrollToItem(msgs.lastIndex)
            }
        }

        val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) { perm.launch(Manifest.permission.READ_CONTACTS) }

        val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK)
                r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { submit(it) }
        }

        Scaffold(topBar = {
            TopAppBar(title = { Text("Assistente") }, actions = {
                IconButton(onClick = { showCfg = !showCfg }) { Icon(Icons.Default.Settings, "Config") }
            })
        }) { pad ->
            Column(Modifier.padding(pad).padding(12.dp).fillMaxSize()) {
                if (showCfg) {
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it; prefs.edit().putString("key", it).apply() },
                        label = { Text("Chave Gemini (grátis, opcional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Text("Crie em aistudio.google.com/apikey. Sem chave, só comandos simples funcionam.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(msgs) { m ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
                            Surface(
                                color = if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.medium
                            ) { Text(m.text, Modifier.padding(10.dp)) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Peça algo...") })
                    IconButton(onClick = {
                        voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("pt", "BR").toLanguageTag())
                        })
                    }) { Icon(Icons.Default.Mic, "Voz") }
                    IconButton(onClick = { submit(input) }) { Icon(Icons.Default.Send, "Enviar") }
                }
            }
        }
    }
}
