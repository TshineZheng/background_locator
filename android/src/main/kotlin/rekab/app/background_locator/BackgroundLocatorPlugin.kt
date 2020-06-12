package rekab.app.background_locator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import rekab.app.background_locator.Keys.Companion.ARG_ACCURACY
import rekab.app.background_locator.Keys.Companion.ARG_CALLBACK
import rekab.app.background_locator.Keys.Companion.ARG_CALLBACK_DISPATCHER
import rekab.app.background_locator.Keys.Companion.ARG_INTERVAL
import rekab.app.background_locator.Keys.Companion.ARG_NOTIFICATION_CALLBACK
import rekab.app.background_locator.Keys.Companion.ARG_NOTIFICATION_ICON
import rekab.app.background_locator.Keys.Companion.ARG_NOTIFICATION_MSG
import rekab.app.background_locator.Keys.Companion.ARG_NOTIFICATION_TITLE
import rekab.app.background_locator.Keys.Companion.ARG_SETTINGS
import rekab.app.background_locator.Keys.Companion.ARG_WAKE_LOCK_TIME
import rekab.app.background_locator.Keys.Companion.CALLBACK_DISPATCHER_HANDLE_KEY
import rekab.app.background_locator.Keys.Companion.CALLBACK_HANDLE_KEY
import rekab.app.background_locator.Keys.Companion.CHANNEL_ID
import rekab.app.background_locator.Keys.Companion.METHOD_PLUGIN_INITIALIZE_SERVICE
import rekab.app.background_locator.Keys.Companion.METHOD_PLUGIN_IS_REGISTER_LOCATION_UPDATE
import rekab.app.background_locator.Keys.Companion.METHOD_PLUGIN_REGISTER_LOCATION_UPDATE
import rekab.app.background_locator.Keys.Companion.METHOD_PLUGIN_UN_REGISTER_LOCATION_UPDATE
import rekab.app.background_locator.Keys.Companion.NOTIFICATION_ACTION
import rekab.app.background_locator.Keys.Companion.NOTIFICATION_CALLBACK_HANDLE_KEY
import rekab.app.background_locator.Keys.Companion.SHARED_PREFERENCES_KEY


class BackgroundLocatorPlugin
    : MethodCallHandler, FlutterPlugin, PluginRegistry.NewIntentListener, ActivityAware {
    private var locatorClient: AMapLocationClient? = null
    private var locationListener: AMapLocationListener? = null
    private var context: Context? = null
    private var activity: Activity? = null

    companion object {
        @JvmStatic
        var AMPLOCATION = "amplocation"

        @JvmStatic
        private var channel: MethodChannel? = null

        @JvmStatic
        private fun registerLocator(context: Context,
                                    client: AMapLocationClient,
                                    listener: AMapLocationListener,
                                    args: Map<Any, Any>,
                                    result: Result?) {
            if (IsolateHolderService.isRunning) {
                // The service is running already
                Log.d("BackgroundLocatorPlugin", "Locator service is already running")
                result?.success(true)
                return
            }

            val callbackHandle = args[ARG_CALLBACK] as Long
            setCallbackHandle(context, CALLBACK_HANDLE_KEY, callbackHandle)

            val notificationCallback = args[ARG_NOTIFICATION_CALLBACK] as? Long
            setCallbackHandle(context, NOTIFICATION_CALLBACK_HANDLE_KEY, notificationCallback)

            val settings = args[ARG_SETTINGS] as Map<*, *>

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_DENIED) {

                val msg = "'registerLocator' requires the ACCESS_FINE_LOCATION permission."
                result?.error(msg, null, null)
            }

            startIsolateService(context, settings)

            client.setLocationOption(getLocationRequest(settings))
            client.setLocationListener(listener)
            client.startLocation()

            result?.success(true)
        }

        @JvmStatic
        private fun startIsolateService(context: Context, settings: Map<*, *>) {
            val intent = Intent(context, IsolateHolderService::class.java)
            intent.action = IsolateHolderService.ACTION_START
            intent.putExtra(ARG_NOTIFICATION_TITLE, settings[ARG_NOTIFICATION_TITLE] as String)
            intent.putExtra(ARG_NOTIFICATION_MSG, settings[ARG_NOTIFICATION_MSG] as String)
            intent.putExtra(ARG_NOTIFICATION_ICON, settings[ARG_NOTIFICATION_ICON] as String)

            if (settings.containsKey(ARG_WAKE_LOCK_TIME)) {
                intent.putExtra(ARG_WAKE_LOCK_TIME, settings[ARG_WAKE_LOCK_TIME] as Int)
            }

            ContextCompat.startForegroundService(context, intent)
        }

        @JvmStatic
        private fun stopIsolateService(context: Context) {
            val intent = Intent(context, IsolateHolderService::class.java)
            intent.action = IsolateHolderService.ACTION_SHUTDOWN
            ContextCompat.startForegroundService(context, intent)
        }

        @JvmStatic
        private fun initializeService(context: Context, args: Map<Any, Any>) {
            val callbackHandle: Long = args[ARG_CALLBACK_DISPATCHER] as Long
            setCallbackDispatcherHandle(context, callbackHandle)
        }

//        @JvmStatic
//        private fun getLocatorPendingIndent(context: Context): PendingIntent {
//            val intent = Intent(context, LocatorBroadcastReceiver::class.java)
//            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
//        }

        @JvmStatic
        private fun getLocationRequest(settings: Map<*, *>): AMapLocationClientOption {
            val locationRequest = AMapLocationClientOption()

            val interval: Long = (settings[ARG_INTERVAL] as Int * 1000).toLong()
            locationRequest.interval = interval
//            locationRequest.fastestInterval = interval
//            locationRequest.maxWaitTime = interval

            val accuracyKey = settings[ARG_ACCURACY] as Int
            locationRequest.locationMode = getAccuracy(accuracyKey)

//            val distanceFilter = settings[ARG_DISTANCE_FILTER] as Double
//            locationRequest.smallestDisplacement = distanceFilter.toFloat()

            return locationRequest

//            val mOption = AMapLocationClientOption()
//            mOption.locationMode = AMapLocationMode.Hight_Accuracy //可选，设置定位模式，可选的模式有高精度、仅设备、仅网络。默认为高精度模式
//            mOption.isGpsFirst = false //可选，设置是否gps优先，只在高精度模式下有效。默认关闭
//            mOption.httpTimeOut = 30000 //可选，设置网络请求超时时间。默认为30秒。在仅设备模式下无效
//            mOption.interval = 2000 //可选，设置定位间隔。默认为2秒
//            mOption.isNeedAddress = true //可选，设置是否返回逆地理地址信息。默认是true
//            mOption.isOnceLocation = false //可选，设置是否单次定位。默认是false
//            mOption.isOnceLocationLatest = false //可选，设置是否等待wifi刷新，默认为false.如果设置为true,会自动变为单次定位，持续定位时不要使用
//            AMapLocationClientOption.setLocationProtocol(AMapLocationProtocol.HTTP) //可选， 设置网络请求的协议。可选HTTP或者HTTPS。默认为HTTP
//            mOption.isSensorEnable = false //可选，设置是否使用传感器。默认是false
//            mOption.isWifiScan = true //可选，设置是否开启wifi扫描。默认为true，如果设置为false会同时停止主动刷新，停止以后完全依赖于系统刷新，定位位置可能存在误差
//            mOption.isLocationCacheEnable = true //可选，设置是否使用缓存定位，默认为true
//            mOption.geoLanguage = AMapLocationClientOption.GeoLanguage.DEFAULT //可选，设置逆地理信息的语言，默认值为默认语言（根据所在地区选择语言）
//
//            return mOption
        }

        @JvmStatic
        private fun getAccuracy(key: Int): AMapLocationClientOption.AMapLocationMode {
            return when (key) {
                0 -> AMapLocationClientOption.AMapLocationMode.Battery_Saving
                1 -> AMapLocationClientOption.AMapLocationMode.Battery_Saving
                2 -> AMapLocationClientOption.AMapLocationMode.Device_Sensors
                3 -> AMapLocationClientOption.AMapLocationMode.Device_Sensors
                4 -> AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                else -> AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            }
        }

        @JvmStatic
        private fun removeLocator(context: Context,
                                  client: AMapLocationClient,
                                  listener: AMapLocationListener) {
            if (!IsolateHolderService.isRunning) {
                // The service is not running
                Log.d("BackgroundLocatorPlugin", "Locator service is not running, nothing to stop")
                return
            }

            client.stopLocation()
            client.unRegisterLocationListener(listener)
            stopIsolateService(context)
        }

        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        private fun isRegisterLocator(context: Context,
                                      result: Result?) {
            if (IsolateHolderService.isRunning) {
                result?.success(true)
            } else {
                result?.success(false)
            }
            return
        }

        @JvmStatic
        private fun setCallbackDispatcherHandle(context: Context, handle: Long) {
            context.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(CALLBACK_DISPATCHER_HANDLE_KEY, handle)
                    .apply()
        }

        @JvmStatic
        fun setCallbackHandle(context: Context, key: String, handle: Long?) {
            if (handle == null) {
                return
            }

            context.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(key, handle)
                    .apply()
        }

        @JvmStatic
        fun getCallbackHandle(context: Context, key: String): Long {
            return context.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                    .getLong(key, 0)
        }

        @JvmStatic
        fun registerAfterBoot(context: Context) {
            val settings = PreferencesManager.getSettings(context)

            val plugin = BackgroundLocatorPlugin()
            plugin.init(context)

            initializeService(context, settings)
            registerLocator(context,
                    plugin.locatorClient!!,
                    plugin.locationListener!!,
                    settings, null)
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            METHOD_PLUGIN_INITIALIZE_SERVICE -> {
                val args: Map<Any, Any> = call.arguments()

                // save callback dispatcher to use it when device reboots
                PreferencesManager.saveCallbackDispatcher(context!!, args)

                initializeService(context!!, args)
                result.success(true)
            }
            METHOD_PLUGIN_REGISTER_LOCATION_UPDATE -> {
                val args: Map<Any, Any> = call.arguments()

                // save setting to use it when device reboots
                PreferencesManager.saveSettings(context!!, args)

                registerLocator(context!!,
                        locatorClient!!,
                        locationListener!!,
                        args,
                        result)
            }
            METHOD_PLUGIN_UN_REGISTER_LOCATION_UPDATE -> {
                removeLocator(context!!, locatorClient!!, locationListener!!)
                result.success(true)
            }
            METHOD_PLUGIN_IS_REGISTER_LOCATION_UPDATE -> {
                isRegisterLocator(context!!, result)
            }
            else -> result.notImplemented()
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        onAttachedToEngine(binding.applicationContext, binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        locatorClient?.onDestroy()
    }

    private fun onAttachedToEngine(context: Context, messenger: BinaryMessenger) {
        val plugin = BackgroundLocatorPlugin()
        plugin.init(context)
        channel = MethodChannel(messenger, CHANNEL_ID)
        channel?.setMethodCallHandler(plugin)
    }

    fun init(ctx: Context) {
        this.context = ctx
        this.locatorClient = AMapLocationClient(context)
        this.locationListener = AMapLocationListener { location ->
            if (location != null) {
                val intent = Intent(context, LocatorBroadcastReceiver::class.java)
                val bundle = Bundle()
                bundle.putParcelable(AMPLOCATION, location)
                intent.putExtras(bundle)
                context!!.sendBroadcast(intent)
            }
        }
    }

    override fun onNewIntent(intent: Intent?): Boolean {
        if (intent?.action != NOTIFICATION_ACTION) {
            // this is not our notification
            return false
        }

        val notificationCallback = getCallbackHandle(activity!!, NOTIFICATION_CALLBACK_HANDLE_KEY)
        if (notificationCallback > 0 && IsolateHolderService._backgroundFlutterView != null) {
            val backgroundChannel = MethodChannel(IsolateHolderService._backgroundFlutterView,
                    Keys.BACKGROUND_CHANNEL_ID)
            Handler(activity?.mainLooper)
                    .post {
                        backgroundChannel.invokeMethod(Keys.BCM_NOTIFICATION_CLICK,
                                hashMapOf(ARG_NOTIFICATION_CALLBACK to notificationCallback))
                    }
        }

        return true
    }

    override fun onDetachedFromActivity() {
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addOnNewIntentListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
    }
}
