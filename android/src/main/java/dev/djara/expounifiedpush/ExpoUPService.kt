package dev.djara.expounifiedpush

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import expo.modules.kotlin.modules.Module
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * The config plugin `withPushPayloadRenderer` writes the classname of the app custom payload renderer (if any)
 * to a `<meta-data>` block with this name in the AndroidManifest file.
 * This is used to read the class from the classname via reflection in `ExpoUPService`.
 */
const val PAYLOAD_RENDERER_META_DATA = "dev.djara.expounifiedpush.PAYLOAD_RENDERER"

class ExpoUPService : PushService() {
    val TAG = "ExpoUPService"
    private var _module: Module? = null

    fun setModule(m: Module) {
        _module = m
    }

    /** tracking lookup of custom renderer as a separate variable because payloadRenderer could actually be null (non-existant) */
    private var payloadRendererResolved = false
    private var payloadRenderer: PushPayloadRenderer? = null

    /** Resolve the app-registered [PushPayloadRenderer], if any.
     *  Caches the result (including the non-existant case) after the first lookup. */
    private fun resolvePayloadRenderer(context: Context): PushPayloadRenderer? {
        if (payloadRendererResolved) {
            return payloadRenderer
        }
        payloadRendererResolved = true
        payloadRenderer = kotlin.runCatching {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            )
            val className = appInfo.metaData?.getString(PAYLOAD_RENDERER_META_DATA)
                ?: return@runCatching null
            Class.forName(className).getDeclaredConstructor().newInstance() as? PushPayloadRenderer
        }.onFailure { err ->
            Log.e(TAG, "Error resolving custom PushPayloadRenderer: $err")
        }.getOrNull()
        return payloadRenderer
    }

    private fun sendPushEvent(action: String, data: Bundle) {
        val payload = Bundle()
        payload.putBundle("data", data)
        payload.putString("action", action)

        val module = _module
        if (module != null) {
            module.sendEvent("message", payload)
        } else {
            Log.e(TAG, "sendPushEvent called without a reference to the expo module")
        }
    }

    private fun sendErrorEvent(err: Throwable) {
        val data = Bundle()
        data.putString("message", err.message)
        data.putString("stackTrace", err.stackTraceToString())
        sendPushEvent("error", data)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onMessage(message: PushMessage, instance: String) {
        val data = Bundle()
        if (message.decrypted) {
            data.putString("message", String(message.content))
        } else {
            data.putByteArray("message", message.content)
        }

        data.putBoolean("decrypted", message.decrypted)
        data.putString("instance", instance)
        Log.d(TAG, "sending \"message\" action with data: $data")
        sendPushEvent("message", data)

        if (message.decrypted) {
            kotlin.runCatching {
                val decrypted = String(message.content)
                val renderer = resolvePayloadRenderer(applicationContext)
                val content = if (renderer != null) {
                    renderer.render(applicationContext, instance, decrypted)
                } else {
                    renderJsonPayload(decrypted)
                }
                if (content != null) {
                    renderNotification(content)
                }
            }.onFailure { err ->
                Log.e(TAG, "Error displaying notification: $err")
                sendErrorEvent(err)
            }
        }
    }

    /** The default payload renderer: expects a JSON string matching the fields in [NotificationContent] */
    private fun renderJsonPayload(message: String): NotificationContent? {
        val data = kotlin.runCatching {
            val json = Json.parseToJsonElement(message)
            json.jsonObject
        }.onFailure { err ->
            Log.e(TAG, "Error parsing notification JSON object: $err")
            sendErrorEvent(err)
        }.getOrNull() ?: return null

        val id = data["id"]?.jsonPrimitive?.long
        if (id == null) {
            Log.w(TAG, "Not sending notification without 'id' in json body")
            return null
        }

        return NotificationContent(
            id = id.toInt(),
            title = data["title"]?.jsonPrimitive?.content,
            body = data["body"]?.jsonPrimitive?.content,
            imageUrl = data["imageUrl"]?.jsonPrimitive?.content,
            number = data["number"]?.jsonPrimitive?.int,
            silent = data["silent"]?.jsonPrimitive?.boolean,
            type = data["type"]?.jsonPrimitive?.content,
            url = data["url"]?.jsonPrimitive?.content,
        )
    }

    /** Entry point for the `showLocalNotification` in the JS side.
     *  This skips renderer resolution and assumes
     *  the caller will send `message` as a JSON string matching `NotificationContent` */
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun showLocalNotification(message: String) {
        val content = renderJsonPayload(message) ?: return
        renderNotification(content)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun renderNotification(content: NotificationContent) {
        createNotificationChannel(content.type)

        val channel = getNotificationChannelId(content.type)
        val notification =
            NotificationCompat.Builder(this, channel)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(content.title)
                .setContentText(content.body)
                .setTicker(content.title)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
                .setContentIntent(getOpenUrlIntent(content.url))
                .setAutoCancel(true)

        if (content.silent != null) {
            notification.setSilent(content.silent)
        }

        if (content.number != null) {
            notification.setNumber(content.number)
        }

        if (content.imageUrl !== null) {
            runBlocking {
                val bitmap = urlToBitmap(content.imageUrl)
                notification.setLargeIcon(bitmap)
            }
        }

        NotificationManagerCompat.from(this).notify(content.id, notification.build())
    }

    private suspend fun urlToBitmap(url: String): Bitmap {
        val bitmap = withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection

            connection.doInput = true
            connection.connect()

            val bitmap = BitmapFactory.decodeStream(connection.inputStream)
            connection.disconnect()

            return@withContext bitmap
        }
        return bitmap
    }

    private fun getNotificationChannelId(type: String?): String {
        val id = applicationContext.packageName
        val channel = "$id:unified_push_channel:$type"
        return channel
    }

    private fun getAppName(): String {
        val pm = applicationContext.packageManager
        val info = applicationContext.applicationInfo
        return pm.getApplicationLabel(info).toString()
    }

    private fun getNotificationChannelName(type: String?): String {
        val appName = getAppName()
        val text = type ?: "$appName notifications"
        return text
    }

    private fun getNotificationChannelDescription(type: String?): String {
        val appName = getAppName()
        val text = "$appName $type notifications"
        return text
    }

    private fun createNotificationChannel(type: String?) {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is not in the Support Library.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val id = getNotificationChannelId(type)
            val name = getNotificationChannelName(type)
            val descriptionText = getNotificationChannelDescription(type)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel =
                NotificationChannel(id, name, importance).apply {
                    description = descriptionText
                }
            // Register the channel with the system.
            NotificationManagerCompat.from(this@ExpoUPService).createNotificationChannel(channel)
        }
    }

    private fun getOpenUrlIntent(url: String?): PendingIntent {
        var intent = applicationContext.packageManager.getLaunchIntentForPackage(
            applicationContext.packageName
        )

        if (url != null) {
            intent = Intent(Intent.ACTION_VIEW, url.toUri().normalizeScheme())
        }

        intent?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val data = Bundle()
        data.putString("url", endpoint.url)
        data.putString("pubKey", endpoint.pubKeySet?.pubKey)
        data.putString("auth", endpoint.pubKeySet?.auth)
        data.putString("instance", instance)
        Log.d(TAG, "sending \"registered\" action with data: $data")
        sendPushEvent("registered", data)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        val data = Bundle()
        data.putString("reason", reason.name)
        data.putString("instance", instance)
        Log.d(TAG, "sending \"registrationFailed\" action with data: $data")
        sendPushEvent("registrationFailed", data)
    }

    override fun onUnregistered(instance: String) {
        val data = Bundle()
        data.putString("instance", instance)
        Log.d(TAG, "sending \"unregistered\" action with data: $data")
        sendPushEvent("unregistered", data)
    }
}
