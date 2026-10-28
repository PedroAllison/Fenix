package com.pedro.assistente

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import java.text.Normalizer

object Actions {
    fun run(ctx: Context, c: Command): String = try {
        when (c.action) {
            "calendar" -> calendar(ctx, c)
            "message" -> message(ctx, c)
            "open_app" -> openApp(ctx, c.app)
            else -> c.reply.ifBlank { "Ok." }
        }
    } catch (e: Exception) {
        "Não consegui executar: ${e.message}"
    }

    private fun start(ctx: Context, i: Intent) { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    private fun calendar(ctx: Context, c: Command): String {
        val begin = if (c.startMillis > 0) c.startMillis else System.currentTimeMillis() + 3600_000
        start(ctx, Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, c.title.ifBlank { "Compromisso" })
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + c.durationMin * 60_000L)
        })
        return "Abrindo o calendário com \"${c.title}\". Toque em Salvar para confirmar."
    }

    private fun message(ctx: Context, c: Command): String {
        val number = resolveNumber(ctx, c.contact)
            ?: return "Não achei o número de \"${c.contact}\" (verifique a permissão de contatos)."
        if (c.channel == "sms") {
            start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", c.message))
        } else {
            val n = number.filter { it.isDigit() }.let { if (it.length <= 11) "55$it" else it }
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n?text=" + Uri.encode(c.message))))
        }
        return "Mensagem para ${c.contact} pronta. Toque em Enviar para confirmar."
    }

    private fun resolveNumber(ctx: Context, who: String): String? {
        if (who.count { it.isDigit() } >= 8) return who
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$who%"), null
        )?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun norm(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val q = norm(name)
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = apps.firstOrNull { norm(it.loadLabel(pm).toString()) == q }
            ?: apps.firstOrNull { norm(it.loadLabel(pm).toString()).contains(q) }
            ?: return "Não encontrei o app \"$name\"."
        start(ctx, pm.getLaunchIntentForPackage(hit.activityInfo.packageName)!!)
        return "Abrindo ${hit.loadLabel(pm)}."
    }
}
