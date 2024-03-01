package dev.fluttercommunity.workmanager

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation
import java.lang.reflect.Method
import java.util.Random


/***
 * A simple worker that will post your input back to your Flutter application.
 *
 * It will block the background thread until a value of either true or false is received back from Flutter code.
 */
class BackgroundWorker(
    applicationContext: Context,
    private val workerParams: WorkerParameters
) : ListenableWorker(applicationContext, workerParams), MethodChannel.MethodCallHandler {

    private lateinit var backgroundChannel: MethodChannel

    companion object {
        const val TAG = "BackgroundWorker"

        const val PAYLOAD_KEY = "be.tramckrijte.workmanager.INPUT_DATA"
        const val DART_TASK_KEY = "be.tramckrijte.workmanager.DART_TASK"
        const val IS_IN_DEBUG_MODE_KEY = "be.tramckrijte.workmanager.IS_IN_DEBUG_MODE_KEY"

        const val BACKGROUND_CHANNEL_NAME =
            "be.tramckrijte.workmanager/background_channel_work_manager"
        const val BACKGROUND_CHANNEL_INITIALIZED = "backgroundChannelInitialized"

        // This name references a class in the belimo_assistant repository
        const val pluginRegistrantClassName = "ch.belimo.belas.WorkmanagerPluginRegistrant"

        private val flutterLoader = FlutterLoader()
    }

    private val payload
        get() = workerParams.inputData.getString(PAYLOAD_KEY)

    private val dartTask
        get() = workerParams.inputData.getString(DART_TASK_KEY)!!

    private val isInDebug
        get() = workerParams.inputData.getBoolean(IS_IN_DEBUG_MODE_KEY, false)

    private val randomThreadIdentifier = Random().nextInt()
    private var engine: FlutterEngine? = null

    private var startTime: Long = 0

    private var completer: CallbackToFutureAdapter.Completer<Result>? = null

    private var resolvableFuture = CallbackToFutureAdapter.getFuture { completer ->
        this.completer = completer
        null
    }

    override fun startWork(): ListenableFuture<Result> {
        startTime = System.currentTimeMillis()

        // The engine will be destroyed when the task is finished. As a result, the onDetachedFromEngine hook of all attached
        // plugins will be called. This can lead to unexpected behaviour in plugins that are not designed to be used in multiple
        // Flutter engines. For this reason, automaticallyRegisterPlugins is disabled. The client of WorkmanagerPlugin is
        // responsible for registering the plugins manually via the WorkmanagerPluginRegistrant.
        engine = FlutterEngine(applicationContext, arrayOf(), false)

        if (!flutterLoader.initialized()) {
            flutterLoader.startInitialization(applicationContext)
        }

        flutterLoader.ensureInitializationCompleteAsync(
            applicationContext,
            null,
            Handler(Looper.getMainLooper())
        ) {
            val callbackHandle = SharedPreferenceHelper.getCallbackHandle(applicationContext)
            val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
            val dartBundlePath = flutterLoader.findAppBundlePath()

            if (isInDebug) {
                DebugHelper.postTaskStarting(
                    applicationContext,
                    randomThreadIdentifier,
                    dartTask,
                    payload,
                    callbackHandle,
                    callbackInfo,
                    dartBundlePath
                )
            }

            engine?.let { engine ->
                registerPlugins(engine)
                backgroundChannel = MethodChannel(engine.dartExecutor, BACKGROUND_CHANNEL_NAME)
                backgroundChannel.setMethodCallHandler(this@BackgroundWorker)

                engine.dartExecutor.executeDartCallback(
                    DartExecutor.DartCallback(
                        applicationContext.assets,
                        dartBundlePath,
                        callbackInfo
                    )
                )
            }
        }

        return resolvableFuture
    }


    /**
     *  Uses reflections to trigger the registration of the Workmanager plugins, which are defined
     *  by the client in ch.belimo.belas.WorkmanagerPluginRegistrant.
     *  This implementation is inspired by the automatic plugin registration from flutter
     *  via GeneratedPluginRegistrant.
     *
     *  @see <a href="https://docs.google.com/document/d/1xNkBmcdVL1yEXqtZ65KzTwfr5UXDD05VVKYXIXGX7p8/edit#heading=h.pub7jnop54q0">Automatic plugin registration</a>
     */
    private fun registerPlugins(engine: FlutterEngine) {
        try {
            Log.i(TAG, "Registering WorkmanagerPluginRegistrant...")
            val registrantClass = Class.forName(pluginRegistrantClassName)
            val registrantCompanionInstance = registrantClass.getDeclaredField("Companion").get(null)
            val registrantCompanion = Class.forName("$pluginRegistrantClassName\$Companion")

            val registerWith: Method = registrantCompanion.getDeclaredMethod("registerWith", FlutterEngine::class.java)
            registerWith.invoke(registrantCompanionInstance, engine)
            Log.i(TAG, "Successfully registered WorkmanagerPluginRegistrant")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register workmanager plugins via WorkmanagerPluginRegistrant", e)
            throw e
        }
    }

    override fun onStopped() {
        stopEngine(null)
    }

    private fun stopEngine(result: Result?) {
        val fetchDuration = System.currentTimeMillis() - startTime

        if (isInDebug) {
            DebugHelper.postTaskCompleteNotification(
                applicationContext,
                randomThreadIdentifier,
                dartTask,
                payload,
                fetchDuration,
                result ?: Result.failure()
            )
        }

        // No result indicates we were signalled to stop by WorkManager.  The result is already
        // STOPPED, so no need to resolve another one.
        if (result != null) {
            this.completer?.set(result)
        }

        // If stopEngine is called from `onStopped`, it may not be from the main thread.
        Handler(Looper.getMainLooper()).post {
            engine?.destroy()
            engine = null
        }
    }

    override fun onMethodCall(call: MethodCall, r: MethodChannel.Result) {
        when (call.method) {
            BACKGROUND_CHANNEL_INITIALIZED ->
                backgroundChannel.invokeMethod(
                    "onResultSend",
                    mapOf(DART_TASK_KEY to dartTask, PAYLOAD_KEY to payload),
                    object : MethodChannel.Result {
                        override fun notImplemented() {
                            stopEngine(Result.failure())
                        }

                        override fun error(
                            errorCode: String,
                            errorMessage: String?,
                            errorDetails: Any?
                        ) {
                            Log.e(TAG, "errorCode: $errorCode, errorMessage: $errorMessage")
                            stopEngine(Result.failure())
                        }

                        override fun success(receivedResult: Any?) {
                            val wasSuccessFul = receivedResult?.let { it as Boolean? } == true
                            stopEngine(if (wasSuccessFul) Result.success() else Result.retry())
                        }
                    }
                )
        }
    }
}
