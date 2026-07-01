package dev.djara.expounifiedpush

import android.content.Context

/** 
 * The content shape of all native notifications for a single push message.
 * All payload renderers must generate an object with this shape.
 */
data class NotificationContent(
  val id: Int,
  val title: String? = null,
  val body: String? = null,
  val imageUrl: String? = null,
  val number: Int? = null,
  val silent: Boolean? = null,
  val type: String? = null,
  val url: String? = null,
)

/**
 * Turns a decrypted push payload into a notification content object.
 * This can run even when the app is killed or backgrounded so
 * implementations must not depend on the JS bridge, and should be fast and synchronous.
 */
interface PushPayloadRenderer {
  /**
   * @param context Application context; safe to use even without an Activity.
   * @param instance The `instance` id passed to `registerDevice` and used in other distributor callbacks
   * @param decrypted The decrypted push payload as a string.
   * @return Formated content to show as a notification, or `null` to show nothing
   */
  fun render(context: Context, instance: String, decrypted: String): NotificationContent?
}
