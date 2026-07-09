package io.github.welingtonmonteiro.appmonitor

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/** Turns an [Alert] into an IDE balloon notification in the "App Monitor" group. */
object AlertNotifier {

    fun notify(project: Project, alert: Alert) {
        val type = if (alert.kind == AlertKind.DOWN) NotificationType.ERROR else NotificationType.WARNING
        NotificationGroupManager.getInstance()
            .getNotificationGroup("App Monitor")
            .createNotification("App Monitor", alert.message, type)
            .notify(project)
    }
}
