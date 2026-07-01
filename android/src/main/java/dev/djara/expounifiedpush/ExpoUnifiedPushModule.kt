package dev.djara.expounifiedpush

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import expo.modules.core.utilities.EmulatorUtilities
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import java.io.ByteArrayOutputStream
import androidx.core.graphics.createBitmap
import androidx.core.os.bundleOf


class ExpoUnifiedPushModule : Module() {
  // Each module class must implement the definition function. The definition consists of components
  // that describes the module's functionality and behavior.
  // See https://docs.expo.dev/modules/module-api for more details about available components.
  override fun definition() = ModuleDefinition {
    // Sets the name of the module that JavaScript code will use to refer to the module. Takes a
    // string as an argument.
    // Can be inferred from module's class name, but it's recommended to set it explicitly for
    // clarity.
    // The module will be accessible from `requireNativeModule('ExpoUnifiedPush')` in JavaScript.
    Name("ExpoUnifiedPush")

    // Sets constant properties on the module. Can take a dictionary or a closure that returns a dictionary.
    // Constants("TASK" to TASK)

    // Defines event names that the module can send to JavaScript.
    Events("message")

    Function("getDistributors") {
      val context = appContext.activityProvider?.currentActivity
      if (context != null) {
        val saved = UnifiedPush.getSavedDistributor(context)
        val connected = UnifiedPush.getAckDistributor(context)
        return@Function UnifiedPush.getDistributors(context).map {
          var name = getPackageName(it)
          val isInternal = it == appContext.reactContext?.packageName
          if (isInternal) {
            name = "Internal FCM Distributor"
          }

          return@map mapOf(
            "id" to it,
            "name" to name,
            "icon" to getDistributorIcon(it),
            "isInternal" to isInternal,
            "isSaved" to (it == saved),
            "isConnected" to (it == connected),
          )
        }
      } else {
        return@Function emptyArray<String>()
      }
    }

    Function("getSavedDistributor") {
      val context = appContext.activityProvider?.currentActivity
      if (context != null) {
        return@Function UnifiedPush.getSavedDistributor(context)
      } else {
        return@Function null
      }
    }

    Function("saveDistributor") { distributor: String? ->
      val context = appContext.activityProvider?.currentActivity
      if (context != null) {
        if (distributor != null) {
          UnifiedPush.saveDistributor(context, distributor)
        } else {
          UnifiedPush.removeDistributor(context)
        }
      }
    }

    AsyncFunction("registerDevice") { vapid: String, instance: String?, promise: Promise ->
      if (EmulatorUtilities.isRunningOnEmulator()) {
        return@AsyncFunction promise.reject(
          CodedException("Cannot register for notifications while running on an emulator")
        )
      }

      val context = appContext.activityProvider?.currentActivity

      if (context != null) {
        val name = getPackageName(context.packageName)
        val saved = UnifiedPush.getSavedDistributor(context)
        if (saved == null) {
          promise.reject(CodedException("You must call `saveDistributor` before trying to register for notifications"))
        } else {
          UnifiedPush.register(
            context,
            instance ?: INSTANCE_DEFAULT,
            "Expo UnifiedPush is trying to register notifications for $name",
            vapid
          )
          promise.resolve()
        }
      } else {
        promise.reject(CodedException("App Context for this module is not ready yet"))
      }
    }

    Function("unregisterDevice") { instance: String? ->
      val context = appContext.activityProvider?.currentActivity
      if (context != null) {
        UnifiedPush.unregister(context, instance ?: INSTANCE_DEFAULT)
      }
    }

    // NOTE: This function is async only to handle the errors with promise rejections, maybe there is a better way to do this
    AsyncFunction("__showLocalNotification") { json: String, promise: Promise ->
      if (distributorService != null)  {
        val permission = ActivityCompat.checkSelfPermission(
          appContext.reactContext!!,
          "android.permission.POST_NOTIFICATIONS"
        )
        if (permission == PackageManager.PERMISSION_GRANTED) {
          distributorService!!.showLocalNotification(json)
          promise.resolve()
        } else {
          promise.reject(CodedException("This application does not have permission to show notifications"))
        }
      } else {
        promise.reject(CodedException("This function should not be called before the service is bound on module creation"))
      }
    }

    Function("__isEmulator") {
      return@Function EmulatorUtilities.isRunningOnEmulator()
    }

    OnCreate {
      kotlin.runCatching {
        bindService()
      }.onFailure { err ->
        Log.e("ExpoUnifiedPushModule", "Error binding service: $err")
        sendEvent("message", bundleOf(
          "action" to "error",
          "data" to bundleOf(
            "message" to err.message,
            "stackTrace" to err.stackTraceToString()
          )
        ))
      }
    }

    OnDestroy {
      kotlin.runCatching {
        unbindService()
      }.onFailure { err ->
        Log.e("ExpoUnifiedPushModule", "Error unbinding service: $err")
        sendEvent("message", bundleOf(
          "action" to "error",
          "data" to bundleOf(
            "message" to err.message,
            "stackTrace" to err.stackTraceToString()
          )
        ))
      }
    }
  }

  private fun getPackageName(id: String): String? {
    val pm = appContext.reactContext?.packageManager ?: return null
    val info = pm.getPackageInfo(id, 0).applicationInfo ?: return null
    return pm.getApplicationLabel(info).toString()
  }

  private fun getDistributorIcon(distributor: String): String? {
    val icon = appContext.reactContext?.packageManager?.getApplicationIcon(distributor)
    val base64 = drawableToBase64(icon) ?: return null

    return "data:image/png;base64,$base64"
  }

  /**
   * Converts an Android Drawable to a Base64 encoded String.
   * Handles BitmapDrawables directly and attempts to render other drawables
   * to a Bitmap.
   *
   * @param drawable The Drawable to convert.
   * @return The Base64 encoded String, or null if conversion fails.
   */
  private fun drawableToBase64(drawable: Drawable?): String? {
    if (drawable == null) {
      return null
    }

    val bitmap: Bitmap? = if (drawable is BitmapDrawable) {
      drawable.bitmap
    } else {
      // Attempt to render other drawable types to a bitmap
      try {
        val width = Math.max(1, drawable.intrinsicWidth)
        val height = Math.max(1, drawable.intrinsicHeight)
        val bmp = createBitmap(width, height)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bmp
      } catch (e: Exception) {
        e.printStackTrace()
        null // Failed to create bitmap from drawable
      }
    }

    if (bitmap == null) {
      return null
    }

    val byteArrayOutputStream = ByteArrayOutputStream()
    // You can choose Bitmap.CompressFormat.JPEG and adjust quality as needed
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
    val byteArray = byteArrayOutputStream.toByteArray()

    // Use Base64.NO_WRAP to avoid line breaks in the output string
    return Base64.encodeToString(byteArray, Base64.NO_WRAP)
  }

  private fun bindService() {
    val context = appContext.activityProvider?.currentActivity
    if (distributorService == null) {
      Intent(context, ExpoUPService::class.java).also { intent ->
        context?.bindService(intent, connection, Context.BIND_AUTO_CREATE)
      }
    }
  }

  private fun unbindService() {
    val context = appContext.activityProvider?.currentActivity
    if (distributorService != null) {
      context?.unbindService(connection)
    }
  }

  private var distributorService: ExpoUPService? = null

  /** Defines callbacks for service binding, passed to bindService().  */
  private val connection = object : ServiceConnection {
    override fun onServiceConnected(className: ComponentName, service: IBinder) {
      val binder = service as PushService.PushBinder
      val upService = binder.getService() as ExpoUPService
      upService.setModule(this@ExpoUnifiedPushModule)
      distributorService = upService
    }

    override fun onServiceDisconnected(arg0: ComponentName) {
      distributorService = null
    }
  }
}
