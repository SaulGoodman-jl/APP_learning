/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2.basic.fragments

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.core.graphics.drawable.toDrawable
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.android.camera.utils.OrientationLiveData
import com.example.android.camera.utils.computeExifOrientation
import com.example.android.camera.utils.getPreviewOutputSize
import com.example.android.camera2.basic.CameraActivity
import com.example.android.camera2.basic.R
import com.example.android.camera2.basic.databinding.FragmentCameraBinding
import com.vikas.gally.Gally
import com.vikas.gally.util.Decorator

import com.yatoooon.screenadaptation.ScreenAdapterTools
import kotlinx.coroutines.*
import java.io.*
import java.lang.Runnable
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.io.path.Path


class CameraFragment : Fragment(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var accelerometer: Sensor

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            fragmentCameraBinding.overlay.background = Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            fragmentCameraBinding.overlay.postDelayed({
                // Remove white flash animation
                fragmentCameraBinding.overlay.background = null
            }, CameraActivity.ANIMATION_FAST_MILLIS)
        }
    }

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private lateinit var session: CameraCaptureSession

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        ScreenAdapterTools.getInstance().loadView(fragmentCameraBinding.root)

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentCameraBinding.captureButton.setOnApplyWindowInsetsListener { v, insets ->
            v.translationX = (-insets.systemWindowInsetRight).toFloat()
            v.translationY = (-insets.systemWindowInsetBottom).toFloat()
            insets.consumeSystemWindowInsets()
        }

        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {
                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                        fragmentCameraBinding.viewFinder.display,
                        characteristics,
                        SurfaceHolder::class.java
                )
                Log.d(TAG, "View finder size: ${fragmentCameraBinding.viewFinder.width} x ${fragmentCameraBinding.viewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")
                fragmentCameraBinding.viewFinder.setAspectRatio(
                        previewSize.width,
                        previewSize.height
                )

                // To ensure that size is set, initialize camera in the view's thread
                view.post { initializeCamera() }
            }
        })

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer { orientation ->
                //Log.d(TAG, "Orientation changed: $orientation")
            })
        }
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating capture request
     * - Sets up the still image capture listeners
     */
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        // Initialize an image reader which will be used to capture still photos
        val size = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(args.pixelFormat).maxByOrNull { it.height * it.width }!!
        imageReader = ImageReader.newInstance(
                size.width, size.height, args.pixelFormat, IMAGE_BUFFER_SIZE)

        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(fragmentCameraBinding.viewFinder.holder.surface, imageReader.surface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        val captureRequest = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(fragmentCameraBinding.viewFinder.holder.surface) }


        //自动对焦
        // Get the available autofocus modes for the camera
        val availableAFModes = characteristics.get(
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()

        if (availableAFModes.isNotEmpty() &&
                (availableAFModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ||
                        availableAFModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE))) {
            // 相机支持自动对焦，设置自动对焦模式
            captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        // This will keep sending the capture request as frequently as possible until the
        // session is torn down or session.stopRepeating() is called
        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

        // Listen to the capture button
        fragmentCameraBinding.captureButton.setOnClickListener {

            // Disable click listener to prevent multiple requests simultaneously in flight
            it.isEnabled = false

            // Perform I/O heavy operations in a different scope
            lifecycleScope.launch(Dispatchers.IO) {
                takePhoto().use { result ->
                    Log.d(TAG, "Result received: $result")

                    // Save the result to disk
                    val output = saveResult(result)
//                    val parentPath = this@CameraFragment.context?.let { it1 -> DCIMUtil.getDCIMPath(it1) }

                    Log.d(TAG, "Image saved: ${output.absolutePath}")
                    // If the result is a JPEG file, update EXIF metadata with orientation info
                    if (output.extension.equals("jpg")) {
                        val exif = ExifInterface(output.absolutePath)
                        exif.setAttribute(
                                ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                        Log.e(TAG, "run in here")
                        try {
                            exif.saveAttributes()
                        } catch (e: IOException) {
                            Log.e(TAG, e.message.toString())
                        }
                        Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")
                        //在这里将所有的图片绝对路径写入txt文件
                        val listFile = createNewFile(requireContext())
                        writeTextToFile(listFile, output.absolutePath, true)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@CameraFragment.context, "图片存储成功", Toast.LENGTH_SHORT).show()
                            fragmentCameraBinding.viewImg!!.setImageURI(Uri.fromFile(File(output.absolutePath)));
                        }
                    } else {
                        Log.d(TAG, "Image extension: ${output.extension}")
                    }
                }
                Log.d(TAG, "Image saved")
                // Re-enable click listener after photo is taken
                it.post { it.isEnabled = true }
            }
        }

        fragmentCameraBinding.viewImg?.setOnClickListener(View.OnClickListener {
            val decorator = Decorator.Builder()
                    .setMaxSelection(7)
                    .setTitle("GallySample")
                    .setTickColor(R.color.colorAccent)
                    .build()

            Gally.getInstance()
                    .decorateWith(decorator)
                    .launch(activity)
        })

        //全屏拍摄
        fragmentCameraBinding.viewFinder.setOnClickListener {
            //可视性取反
//            fragmentCameraBinding.tvX?.visibility = if (fragmentCameraBinding.tvX?.visibility==View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
            manager: CameraManager,
            cameraId: String,
            handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(
            device: CameraDevice,
            targets: List<Surface>,
            handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
     * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
     * from the single capture, and outputs a [CombinedCaptureResult] object.
     */
    private suspend fun takePhoto():
            CombinedCaptureResult = suspendCoroutine { cont ->

        // Flush any images left in the image reader
        @Suppress("ControlFlowWithEmptyBody")
        while (imageReader.acquireNextImage() != null) {
        }

        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

        val captureRequest = session.device.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(imageReader.surface) }
        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    timestamp: Long,
                    frameNumber: Long) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                fragmentCameraBinding.viewFinder.post(animationTask)
            }

            override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult) {
                super.onCaptureCompleted(session, request, result)
                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                Log.d(TAG, "Capture result received: $resultTimestamp")
                getDistanceData(result)

                // Set a timeout in case image captured is dropped from the pipeline
                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                // Loop in the coroutine's context until an image with matching timestamp comes
                // We need to launch the coroutine context again because the callback is done in
                //  the handler provided to the `capture` method, not in our coroutine context
                @Suppress("BlockingMethodInNonBlockingContext")
                lifecycleScope.launch(cont.context) {
                    while (true) {

                        // Dequeue images while timestamps don't match
                        val image = imageQueue.take()
                        // TODO(owahltinez): b/142011420
                        // if (image.timestamp != resultTimestamp) continue
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                image.format != ImageFormat.DEPTH_JPEG &&
                                image.timestamp != resultTimestamp) continue
                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                        // Unset the image reader listener
                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        imageReader.setOnImageAvailableListener(null, null)

                        // Clear the queue of images, if there are left
                        while (imageQueue.size > 0) {
                            imageQueue.take().close()
                        }

                        // Compute EXIF orientation metadata
                        val rotation = relativeOrientation.value ?: 0
                        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = computeExifOrientation(rotation, mirrored)

                        // Build the result and resume progress
                        cont.resume(CombinedCaptureResult(
                                image, result, exifOrientation, imageReader.imageFormat))

                        // There is no need to break out of the loop, this coroutine will suspend
                    }
                }
            }
        }, cameraHandler)
    }

    /** Helper function used to save a [CombinedCaptureResult] into a [File] */
    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        when (result.format) {

            // When the format is JPEG or DEPTH JPEG we can simply save the bytes as-is
            ImageFormat.JPEG, ImageFormat.DEPTH_JPEG -> {
                val buffer = result.image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
                try {
                    val output = createFile(requireContext(), "jpg")
                    DCIMUtil.insertMediaPic(requireContext(), output.absolutePath)
                    FileOutputStream(output).use { it.write(bytes) }
                    Log.e(TAG, "图片写入文件......${output.absolutePath}")
                    cont.resume(output)
                } catch (exc: IOException) {
                    //Log.e(TAG, "Unable to write JPEG image to file", exc)
                    cont.resumeWithException(exc)
                }
            }

            // When the format is RAW we use the DngCreator utility library
            ImageFormat.RAW_SENSOR -> {
                val dngCreator = DngCreator(characteristics, result.metadata)
                try {
                    val output = createFile(requireContext(), "dng")
                    FileOutputStream(output).use { dngCreator.writeImage(it, result.image) }
                    cont.resume(output)
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write DNG image to file", exc)
                    cont.resumeWithException(exc)
                }
            }

            // No other formats are supported by this sample
            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    companion object {
        private val TAG = "AirBus"

        /** Maximum number of images that will be held in the reader's buffer */
        private const val IMAGE_BUFFER_SIZE: Int = 3

        /** Maximum time allowed to wait for the result of an image capture */
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        /** Helper data class used to hold capture metadata with their associated image */
        data class CombinedCaptureResult(
                val image: Image,
                val metadata: CaptureResult,
                val orientation: Int,
                val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }

        /**
         * Create a [File] named a using formatted timestamp with the current date and time.
         *
         * @return [File] created.
         */
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.CHINA)
//            return File(context.getExternalFilesDir(null), "IMG_${sdf.format(Date())}.$extension")
//            return File("context.filesDir", "MDAR_${sdf.format(Date())}.$extension")
            //文件夹位置
            val parentPath = DCIMUtil.getDCIMPath(context)
            Log.e(TAG, "file path = $parentPath")
            return File(parentPath, "MDAR_${sdf.format(Date())}.$extension")
        }

        private fun makeFileList(context: Context, content: String): Boolean {
            val path = context.getExternalFilesDir(null)
            val sd_main = File(path, "/mdar")
            var success = true
            if (!sd_main.exists()) {
                success = sd_main.mkdir()
                Log.e(TAG, sd_main.absolutePath)
            }
            if (success) {
                val sd = Path(sd_main.absolutePath, "imglist.txt")
                val isExist = Files.exists(sd)
                if (!isExist) {

                    val sdFile = File(sd_main, "imglist.txt")
                    try {
                        // response is the data written to file
                        sdFile.printWriter().use { out ->
                            out.println(content)
                        }
                        Log.e(TAG, "文件写入成功,文件路径为： " + sdFile.absolutePath)

                    } catch (e: Exception) {
                        // handle the exception
                        Log.e("AirBus", e.cause.toString())
                        return false
                    }
                    return true
                } else {
                    try {
                        Log.e(TAG, "文件已存在")
                        // response is the data written to file
                        File(sd.toString()).printWriter().use { out ->
                            out.println(content)
                        }
                        Log.e(TAG, "文件写入成功,文件路径为： " + File(sd.toString()).absolutePath)
                    } catch (e: Exception) {
                        // handle the exception
                        Log.e("AirBus", e.cause.toString())
                        return false
                    }
                    return true
                }
            }
            return false
        }

        private fun createNewFile(context: Context): File {
            val path = context.getExternalFilesDir(null)
            val sd_main = File(path, "/mdar")
            var success = true
            if (!sd_main.exists()) {
                success = sd_main.mkdir()
                Log.e(TAG, sd_main.absolutePath)
            }
            var file = File(sd_main.absolutePath, "imgList.txt")

            if (file.exists()) {
                return file
            } else {
                file.createNewFile()
                return file
            }
        }

        private fun readLineFromFile(filePath: String): List<String> {
            val file = File(filePath)
            return file.readLines()
        }

        private fun readTextFromFile(filePath: String): String {
            val file = File(filePath)
            return file.readText()
        }

        private fun writeTextToFile(file: File, text: String, isNeedChangeLine: Boolean) {
            var strContent = text
            if (isNeedChangeLine) {
                strContent += "\r\n"
            }
            var randomAccessFile = RandomAccessFile(file, "rwd")
            randomAccessFile.seek(file.length())
            randomAccessFile.write(strContent.toByteArray())
            randomAccessFile.close()
        }

    }

    object DCIMUtil {
        /**
         * 根据 Android Q 区分地址
         * @param context
         * @return
         */
        fun getDCIMPath(context: Context): String {
            var fileName = ""
//            fileName = if (Build.VERSION.SDK_INT >= 31) {
//                context.getExternalFilesDir("")?.absolutePath.toString() + "/current/"
//            } else {
//                when {
//                    "Xiaomi".equals(Build.BRAND, ignoreCase = true) -> { // 小米手机
//                        Environment.getExternalStorageDirectory().path.toString() + "/DCIM/Camera/"
//                    }
//                    "HUAWEI".equals(Build.BRAND, ignoreCase = true) -> {
//                        Environment.getExternalStorageDirectory().path.toString() + "/DCIM/Camera/"
//                    }
//                    "HONOR".equals(Build.BRAND, ignoreCase = true) -> {
//                        Environment.getExternalStorageDirectory().path.toString() + "/DCIM/Camera/"
//                    }
//                    "OPPO".equals(Build.BRAND, ignoreCase = true) -> {
//                        Environment.getExternalStorageDirectory().path.toString() + "/DCIM/Camera/"
//                    }
//                    "vivo".equals(Build.BRAND, ignoreCase = true) -> {
//                        Environment.getExternalStorageDirectory().path.toString() + "/DCIM/Camera/"
//                    }
//                    "samsung".equals(Build.BRAND, ignoreCase = true) -> {
//                        Environment.getExternalStorageDirectory().path.toString() + "/DCIM/Camera/"
//                    }
//                    else -> {
//                        Environment.getExternalStorageDirectory().path.toString() + "/DCIM/"
//                    }
//                }
//            }
            fileName =
                    when {
                        "Xiaomi".equals(Build.BRAND, ignoreCase = true) -> { // 小米手机
                            Environment.getExternalStorageDirectory().path.toString() + "/DCIM/Camera/"
                        }
                        "HUAWEI".equals(Build.BRAND, ignoreCase = true) -> {
                            Environment.getExternalStorageDirectory().path.toString() + "/DCIM/Camera/"
                        }
                        "HONOR".equals(Build.BRAND, ignoreCase = true) -> {
                            Environment.getExternalStorageDirectory().path.toString() + "/DCIM/Camera/"
                        }
                        "OPPO".equals(Build.BRAND, ignoreCase = true) -> {
                            Environment.getExternalStorageDirectory().path.toString() + "/DCIM/Camera/"
                        }
                        "vivo".equals(Build.BRAND, ignoreCase = true) -> {
                            Environment.getExternalStorageDirectory().path.toString() + "/DCIM/Camera/"
                        }
                        "samsung".equals(Build.BRAND, ignoreCase = true) -> {
                            Environment.getExternalStorageDirectory().path.toString() + "/DCIM/Camera/"
                        }
                        else -> {
                            Environment.getExternalStorageDirectory().path.toString() + "/DCIM/"
                        }
                    }

            val file = File(fileName)
            return if (file.mkdirs()) {
                fileName
            } else fileName
        }

        /**
         * 判断android Q  (10 ) 版本
         * @return
         */
        private fun isAndroidQ(): Boolean {
            return Build.VERSION.SDK_INT >= 29
        }

        /**
         * 复制文件
         *
         * @param oldPathName
         * @param newPathName
         * @return
         */
        fun copyFile(oldPathName: String?, newPathName: String?): Boolean {
            return try {
                val oldFile = File(oldPathName)
                if (!oldFile.exists()) {
                    return false
                } else if (!oldFile.isFile) {
                    return false
                } else if (!oldFile.canRead()) {
                    return false
                }
                val fileInputStream = FileInputStream(oldPathName)
                val fileOutputStream = FileOutputStream(newPathName)
                val buffer = ByteArray(1024)
                var byteRead: Int
                while (-1 != fileInputStream.read(buffer).also { byteRead = it }) {
                    fileOutputStream.write(buffer, 0, byteRead)
                }
                fileInputStream.close()
                fileOutputStream.flush()
                fileOutputStream.close()
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }


        /**
         * 插入相册 部分机型适配(区分手机系统版本 Android Q)
         * @param context
         * @param filePath
         * @return
         */
        fun insertMediaPic(context: Context, filePath: String?): Boolean {
            if (TextUtils.isEmpty(filePath)) return false
            val file = File(filePath)
            //判断android Q  (10 ) 版本
            return if (isAndroidQ()) {
                if (!file.exists()) {
                    false
                } else {
                    try {
                        MediaStore.Images.Media.insertImage(
                                context.contentResolver,
                                file.absolutePath,
                                file.name,
                                null
                        )
                        true
                    } catch (e: java.lang.Exception) {
                        e.printStackTrace()
                        false
                    }
                }
            } else {
                val values = ContentValues()
                values.put(MediaStore.Images.Media.DATA, file.absolutePath)
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                values.put(
                        MediaStore.Images.ImageColumns.DATE_TAKEN,
                        System.currentTimeMillis().toString() + ""
                )
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                context.sendBroadcast(
                        Intent(
                                Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                                Uri.parse("file://" + file.absolutePath)
                        )
                )
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
//        startCaptureTimer()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    var previousX = 0f
    var previousY = 0f
    var previousZ = 0f

    override fun onSensorChanged(event: SensorEvent) {
        if (event != null) {
            // x短y长z垂直 Acceleration是指物体加速度的大小是z
            val z = String.format("%.1f", event.values[2]).toFloat()
            val x = Math.round(event.values[0]).toFloat()
            val y = Math.round(event.values[1]).toFloat()

            // Update the x and y axis on the coordinate system
            if (Math.abs(x - previousX) > 1 || Math.abs(y - previousY) > 1 || Math.abs(z - previousZ) > 1) {
                //update
//                fragmentCameraBinding.tvX?.text="X:${x.toInt()}°  Y:${y.toInt()}° Acceleration:${z}"
                previousX = x
                previousY = y
                previousZ = z
            }

        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
    }

    private fun getDistanceData(result: TotalCaptureResult) {
        val mode = result.get(CaptureResult.CONTROL_AF_MODE)
        val state = result.get(CaptureResult.CONTROL_AF_STATE)//2 yes , 6 no
        //CONTROL_AF_REGIONS需要转换，暂不用
        val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
        //LENS_FOCUS_RANGE定值，无用 Pair{0.0 14.285714}
        Log.d(TAG, "mode:$mode;state:$state;focusDistance:$focusDistance;")
        fragmentCameraBinding.distanceText?.text = "Distance\n$focusDistance m"
    }
}
