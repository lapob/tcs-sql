package it.topcash.studio.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** System notifications: interventions assigned to me, new urgent ones. Each intervention is announced once (app and background check agree). */
object Notifier {
    const val OPEN = "it.topcash.studio.OPEN_INTERVENTION"
    const val EXTRA_ID = "intervention"
    private const val KEPT_MS = 3L * 24 * 3600_000

    fun allowed(ctx: Context) = android.os.Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channels(nm: NotificationManager) {
        nm.createNotificationChannel(NotificationChannel("assigned", "Interventi assegnati", NotificationManager.IMPORTANCE_HIGH).apply { description = "Quando qualcuno ti assegna o modifica un tuo intervento" })
        nm.createNotificationChannel(NotificationChannel("urgent", "Interventi urgenti", NotificationManager.IMPORTANCE_HIGH).apply { description = "Quando arriva un nuovo intervento urgente" })
    }

    /** Already announced? Keys are kept three days ("kind:id" → time). */
    @Synchronized private fun firstTime(ctx: Context, key: String): Boolean {
        val p = ctx.getSharedPreferences("notices", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val seen = p.getStringSet("seen", emptySet()).orEmpty().mapNotNull { s -> s.substringBeforeLast('@').takeIf { it.isNotEmpty() }?.let { it to (s.substringAfterLast('@').toLongOrNull() ?: 0L) } }
            .filter { now - it.second < KEPT_MS }.toMap().toMutableMap()
        if (key in seen) return false
        seen[key] = now
        p.edit().putStringSet("seen", seen.map { "${it.key}@${it.value}" }.toSet()).apply()
        return true
    }

    private fun openIntent(ctx: Context, id: String): PendingIntent? {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.setAction(OPEN)?.putExtra(EXTRA_ID, id)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) ?: return null
        return PendingIntent.getActivity(ctx, id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** [kind]: "assigned", "updated", "urgent". */
    fun show(ctx: Context, kind: String, id: String, title: String, client: String, by: String) {
        if (!allowed(ctx) || !firstTime(ctx, "$kind:$id")) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        channels(nm)
        val head = when (kind) { "urgent" -> "Nuovo intervento urgente"; "updated" -> "Intervento aggiornato"; else -> "Nuovo intervento assegnato a te" }
        val text = listOf(client, title).filter { it.isNotBlank() }.joinToString(" · ") + if (by.isNotBlank()) " — da $by" else ""
        val n = Notification.Builder(ctx, if (kind == "urgent") "urgent" else "assigned").setSmallIcon(it.topcash.studio.R.mipmap.ic_launcher)
            .setContentTitle(head).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_EVENT).setContentIntent(openIntent(ctx, id)).build()
        runCatching { nm.notify(("int-$id").hashCode(), n) }
    }
    /** The changes the app saw itself: same rule as the server ([Notice]) and the same "only once". */
    fun fromArchive(ctx: Context, me: Me, prev: List<Intervention>, next: List<Intervention>) {
        fun n(s: String) = s.trim().lowercase()
        val was = prev.associateBy { it.id }
        for (i in next) {
            if (i.source != "app" || i.status == "Chiuso") continue
            val by = i.updatedBy.ifBlank { i.createdBy }; if (n(by) == n(me.name)) continue
            val old = was[i.id]
            val mine = n(i.technician) == n(me.techName) && me.techName.isNotBlank()
            if (mine && (old == null || n(old.technician) != n(me.techName))) show(ctx, "assigned", i.id, i.title, i.clientName, by)
            else if (old == null && i.priority == "Urgente" && (me.role != "tecnico" || i.technician.isBlank())) show(ctx, "urgent", i.id, i.title, i.clientName, by)
        }
    }
}

/**
 * Background check every quarter of an hour (also with the app closed, only with network): sends what was saved without
 * network and asks the server for interventions assigned to me or new urgent ones. Android decides the exact moment
 * (battery saving): the notification may come a few minutes late, never twice.
 */
class NoticeJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch {
            try { run(applicationContext) } catch (_: Throwable) {} finally { jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }

    companion object {
        private const val ID = 4207
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(ID) != null) return
            js.schedule(JobInfo.Builder(ID, ComponentName(ctx, NoticeJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60_000L).setPersisted(true).build())
        }
        fun cancel(ctx: Context) { ctx.getSystemService(JobScheduler::class.java)?.cancel(ID) }

        suspend fun run(ctx: Context) {
            val prefs = ctx.getSharedPreferences("topcash", Context.MODE_PRIVATE)
            prefs.getString("me", null) ?: return // signed out: nothing to check
            val api = Api.fromPrefs(ctx) ?: return
            if (api.bases.size > 1) api.choose()
            Outbox.init(ctx); Outbox.flush(api)
            val since = prefs.getString("notices_at", null) ?: java.time.OffsetDateTime.now().minusMinutes(20).toString()
            val r = api.notices(since)
            r.items.forEach { Notifier.show(ctx, it.kind, it.id, it.title, it.client, it.by) }
            if (r.now.isNotBlank()) prefs.edit().putString("notices_at", r.now).apply()
        }
    }
}
