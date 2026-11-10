package com.example.simplelauncher

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Bildirimi olan uygulamaların paket adları; ana ekran bunlara kırmızı nokta koyar. */
object NotificationDots {
    var packages by mutableStateOf<Set<String>>(emptySet())
        internal set
}

/**
 * Sistemdeki bildirimleri izler. Kullanıcının "Bildirim erişimi" vermesi gerekir
 * (Ayarlar → Bildirimler → Cihaz ve uygulama bildirimleri).
 */
class NotificationDotService : NotificationListenerService() {

    override fun onListenerConnected() = update()

    override fun onListenerDisconnected() {
        NotificationDots.packages = emptySet()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = update()

    override fun onNotificationRemoved(sbn: StatusBarNotification) = update()

    override fun onNotificationRankingUpdate(rankingMap: RankingMap) = update()

    private fun update() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val ranking = currentRanking
        NotificationDots.packages = active
            .filter { sbn ->
                // Sürekli bildirimler (müzik, navigasyon…) ve nokta göstermesi kapalı kanallar sayılmaz.
                val ongoing = sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
                val r = Ranking()
                val badge = ranking == null || !ranking.getRanking(sbn.key, r) || r.canShowBadge()
                !ongoing && badge
            }
            .mapTo(HashSet()) { it.packageName }
    }
}
