package com.pedro.assistente

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

data class Command(
    val action: String,          // calendar | message | open_app | chat | none
    val title: String = "",
    val startMillis: Long = 0,
    val durationMin: Int = 60,
    val contact: String = "",
    val message: String = "",
    val channel: String = "whatsapp",
    val app: String = "",
    val reply: String = ""
)

object Brain {
    private const val MODEL = "gemini-2.5-flash" // modelo com cota gratuita

    /** Tenta regras locais (offline). Se não entender e houver chave, usa o Gemini. */
    fun interpret(text: String, apiKey: String): Command {
        localRules(text)?.let { return it }
        if (apiKey.isNotBlank()) {
            runCatching { gemini(text, apiKey) }.getOrNull()?.let { return it }
        }
        return Command("none", reply = "Não entendi. Tente: \"abrir WhatsApp\", \"marcar dentista amanhã às 15h\" ou \"mandar mensagem para Maria: oi\".")
    }

    // ---------- Regras locais ----------
    private fun localRules(raw: String): Command? {
        val t = raw.trim()
        val l = t.lowercase(Locale("pt", "BR"))

        Regex("^(abrir|abre|abra|iniciar|inicia|jogar)\\s+(o |a )?(.+)$").find(l)?.let {
            return Command("open_app", app = it.groupValues[3].trim())
        }

        Regex("^(mandar|manda|enviar|envia)\\s+(uma\\s+)?(mensagem|msg|zap|whats|whatsapp|sms)?\\s*(para|pra|p/)\\s+(\\S+)\\s*[:,-]?\\s*(.+)$").find(l)?.let {
            val canal = if (it.groupValues[3] == "sms") "sms" else "whatsapp"
            // preserva a capitalização original da mensagem
            val msg = t.substring(t.length - it.groupValues[6].length)
            return Command("message", contact = it.groupValues[5], message = msg, channel = canal)
        }

        Regex("^(marcar|marca|agendar|agenda|criar evento|lembrar de|lembrete)\\s+(.+)$").find(l)?.let {
            val resto = it.groupValues[2]
            val cal = Calendar.getInstance().apply { set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            var dia = false
            when {
                "depois de amanhã" in resto || "depois de amanha" in resto -> { cal.add(Calendar.DAY_OF_YEAR, 2); dia = true }
                "amanhã" in resto || "amanha" in resto -> { cal.add(Calendar.DAY_OF_YEAR, 1); dia = true }
                "hoje" in resto -> dia = true
            }
            Regex("dia (\\d{1,2})").find(resto)?.let { m ->
                val d = m.groupValues[1].toInt()
                if (d < cal.get(Calendar.DAY_OF_MONTH)) cal.add(Calendar.MONTH, 1)
                cal.set(Calendar.DAY_OF_MONTH, d); dia = true
            }
            val hora = Regex("(\\d{1,2})\\s*(?:h|:)\\s*(\\d{2})?").find(resto)
            if (hora != null) {
                cal.set(Calendar.HOUR_OF_DAY, hora.groupValues[1].toInt())
                cal.set(Calendar.MINUTE, hora.groupValues[2].ifBlank { "0" }.toInt())
            } else if (!dia) return null
            else { cal.set(Calendar.HOUR_OF_DAY, 9); cal.set(Calendar.MINUTE, 0) }
            val titulo = resto
                .replace(Regex("(depois de amanh[ãa]|amanh[ãa]|hoje|dia \\d{1,2}|(às|as)?\\s*\\d{1,2}\\s*(h|:)\\s*\\d{0,2})"), "")
                .replace(Regex("\\s+"), " ").trim().replaceFirstChar { c -> c.uppercase() }
            return Command("calendar", title = titulo.ifBlank { "Compromisso" }, startMillis = cal.timeInMillis)
        }
        return null
    }

    // ---------- Gemini ----------
    private fun gemini(text: String, apiKey: String): Command {
        val agora = SimpleDateFormat("yyyy-MM-dd'T'HH:mm EEEE", Locale("pt", "BR")).format(Date())
        val prompt = """
Você é um assistente pessoal brasileiro dentro de um app Android. Agora é $agora (fuso ${TimeZone.getDefault().id}).
Converta o pedido do usuário em UM JSON com os campos:
action: "calendar" | "message" | "open_app" | "chat"
title (calendar), start_iso no formato yyyy-MM-dd'T'HH:mm (calendar), duration_min (calendar, padrão 60),
contact e message e channel ("whatsapp" ou "sms") (message), app (open_app: nome do app/jogo),
reply (texto curto em português; para "chat" é a resposta à pergunta).
Responda SOMENTE o JSON.
Pedido: $text
""".trimIndent()

        val body = JSONObject()
            .put("contents", org.json.JSONArray().put(JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val resp = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
        if (conn.responseCode !in 200..299) error("Gemini ${conn.responseCode}")

        val out = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val j = JSONObject(out)
        val start = j.optString("start_iso").takeIf { it.isNotBlank() }?.let {
            runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(it)?.time }.getOrNull()
        } ?: 0L
        return Command(
            action = j.optString("action", "chat"),
            title = j.optString("title"), startMillis = start,
            durationMin = j.optInt("duration_min", 60),
            contact = j.optString("contact"), message = j.optString("message"),
            channel = j.optString("channel", "whatsapp"),
            app = j.optString("app"), reply = j.optString("reply")
        )
    }
}
