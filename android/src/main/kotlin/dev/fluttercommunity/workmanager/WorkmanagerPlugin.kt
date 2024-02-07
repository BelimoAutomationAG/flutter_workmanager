package dev.fluttercommunity.workmanager

import android.content.Context
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry

class WorkmanagerPlugin : FlutterPlugin {

    private var methodChannel: MethodChannel? = null
    private var workmanagerCallHandler: WorkmanagerCallHandler? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        onAttachedToEngine(binding.applicationContext, binding.binaryMessenger)
    }

    private fun onAttachedToEngine(context: Context, messenger: BinaryMessenger) {
        workmanagerCallHandler = WorkmanagerCallHandler(context)
        methodChannel = MethodChannel(messenger, "be.tramckrijte.workmanager/foreground_channel_work_manager")
        methodChannel?.setMethodCallHandler(workmanagerCallHandler)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        onDetachedFromEngine()
    }

    private fun onDetachedFromEngine() {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        workmanagerCallHandler = null
    }

    companion object {
        var pluginRegistryCallback: PluginRegistry.PluginRegistrantCallback? = null

        /**
         * The [FlutterEngine] of a background task is created without any plugins attached. In order to use plugins in a
         * background task, a [PluginRegistrantV2] must be provided.
         *
         * [pluginRegistrantV2] will be called after the [FlutterEngine] of a background task has been created and is responsible
         * for registering any needed plugins with the [FlutterEngine].
         * [pluginRegistrantV2] must be set before scheduling a background job.
         *
         * In contrast to [pluginRegistryCallback], this is intended for use with the v2 Android embedding.
         */
        @JvmStatic
        var pluginRegistrantV2: PluginRegistrantV2? = null

        @JvmStatic
        fun registerWith(registrar: PluginRegistry.Registrar) {
            val plugin = WorkmanagerPlugin()
            plugin.onAttachedToEngine(registrar.context(), registrar.messenger())
            registrar.addViewDestroyListener {
                plugin.onDetachedFromEngine()
                false
            }
        }

        @Deprecated(message = "Use the Android v2 embedding method.")
        @JvmStatic
        fun setPluginRegistrantCallback(pluginRegistryCallback: PluginRegistry.PluginRegistrantCallback) {
            Companion.pluginRegistryCallback = pluginRegistryCallback
        }
    }
}

interface PluginRegistrantV2 {
    fun registerWith(engine: FlutterEngine)
}
