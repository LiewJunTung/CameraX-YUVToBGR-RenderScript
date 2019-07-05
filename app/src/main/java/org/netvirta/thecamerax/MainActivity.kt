package org.netvirta.thecamerax

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Point
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.renderscript.RenderScript
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions
import kotlinx.android.synthetic.main.activity_main.*

private const val REQUEST_CODE_PERMISSIONS = 10


class MainActivity : AppCompatActivity(), LifecycleOwner {

    private lateinit var manager: CameraManager
    private lateinit var windowSize: Point
    private var rotationDegrees: Int = 0
    private lateinit var options: FirebaseVisionFaceDetectorOptions
    // This is an array of all the permission specified in the manifest
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val display = getWindowManager().getDefaultDisplay()
        windowSize = Point()
        display.getSize(windowSize)
        getCameraCharacteristics(this)
        Log.d("WINDOW", "${windowSize.x} ${windowSize.y}")
        setContentView(R.layout.activity_main)
        initFirebase()
        viewFinder = view_finder

        // Request camera permissions
        if (allPermissionsGranted()) {
            viewFinder.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Every time the provided texture view changes, recompute layout
        viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->

        }

    }

    private fun initFirebase() {
        options = FirebaseVisionFaceDetectorOptions.Builder()
            .setLandmarkMode(FirebaseVisionFaceDetectorOptions.ALL_LANDMARKS)
            .setContourMode(FirebaseVisionFaceDetectorOptions.ALL_CONTOURS)
            .setClassificationMode(FirebaseVisionFaceDetectorOptions.ALL_CLASSIFICATIONS)
            .build()
    }

    // Add this after onCreate

    private lateinit var viewFinder: TextureView

    private fun getCameraCharacteristics(context: Context) {
        manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (manager != null) {
                for (id in manager!!.cameraIdList) {
                    val characteristics = manager!!.getCameraCharacteristics(id)

                    val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)!!

                    val size = characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
                    val mSensorWidth = size.right - size.left
                    val mSensorHeight = size.bottom - size.top
                    if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                        println("BACK $size width: $mSensorWidth height: $mSensorHeight")
                    } else if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                        println("FRONT $size width: $mSensorWidth height: $mSensorHeight")
                    }
                }
            } else {
                throw RuntimeException("Camera Manager is not available.")
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    @SuppressLint("RestrictedApi")
    private fun startCamera() {


        val previewConfig = PreviewConfig.Builder().apply {
            setTargetAspectRatio(Rational(9, 16))
            setTargetResolution(Size(1080, 1920))
            setLensFacing(CameraX.LensFacing.FRONT)

        }.build()

        val preview2 = Preview(previewConfig)
        preview2.setOnPreviewOutputUpdateListener {
            val parent = viewFinder.parent as ViewGroup
            parent.removeView(viewFinder)
            parent.addView(viewFinder, 0)

            viewFinder.surfaceTexture = it.surfaceTexture
//            updateTransform()
        }

        //3840×2160
        val imageCaptureConfig = ImageCaptureConfig.Builder()
            .setBufferFormat(ImageFormat.YUV_420_888)
            .setLensFacing(CameraX.LensFacing.FRONT)
            .setTargetAspectRatio(Rational(16, 9))
            .setTargetResolution(Size(3840, 2160))
            .build()
        val yuvToRgb = YuvToRgbBufferConversion(rs = RenderScript.create(this))
        val imageCapture = ImageCapture(imageCaptureConfig)
        var bitmap: Bitmap?
        capture.setOnClickListener {
            imageCapture.takePicture(object : ImageCapture.OnImageCapturedListener() {

                override fun onCaptureSuccess(image: ImageProxy?, rotationDegrees: Int) {
                    Log.d("CAPTURE", "HENRY ${image!!.planes?.size}")
                    val surfaceRotation = Surface.ROTATION_90
                    val buffer = yuvToRgb.yuvToRgb(
                        image.planes[0].buffer,
                        image.planes[1].buffer,
                        image.planes[2].buffer,
                        image.width,
                        image.height,
                        surfaceRotation
                    )
                    bitmap = if(surfaceRotation == Surface.ROTATION_90 || surfaceRotation == Surface.ROTATION_270) {
                        Bitmap.createBitmap(image.height, image.width, Bitmap.Config.ARGB_8888)
                    } else {
                        Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    }
                    buffer.rewind()
                    bitmap!!.copyPixelsFromBuffer(buffer)
                    your_image.post {
                        your_image.setImageBitmap(bitmap)
                    }
                    image.close()
                }

            })
        }

        CameraX.bindToLifecycle(this, setupAnalyser2(), imageCapture)
    }

    private fun setupAnalyser2(): ImageAnalysis {

        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            // Use a worker thread for image analysis to prevent glitches
            setLensFacing((CameraX.LensFacing.FRONT))
            setTargetAspectRatio(Rational(9, 16))
            setTargetResolution(Size(1080, 1920))
            setTargetRotation(Surface.ROTATION_0)
            val analyzerThread = HandlerThread(
                "CameraAnalyser"
            ).apply { start() }
            setCallbackHandler(Handler(analyzerThread.looper))
        }.build()

        val yuvToRgb = YuvToRgbBufferConversion(rs = RenderScript.create(this))

        var bitmap: Bitmap? = null
        // Build the image analysis use case and instantiate our analyzer
        return ImageAnalysis(analyzerConfig).apply {
            analyzer = ImageAnalysis.Analyzer { image, _ ->

                val surfaceRotation = Surface.ROTATION_90
                val buffer = yuvToRgb.yuvToRgb(
                    image.planes[0].buffer,
                    image.planes[1].buffer,
                    image.planes[2].buffer,
                    image.width,
                    image.height,
                    surfaceRotation
                )
                if (bitmap == null) {
                }

                bitmap = if(surfaceRotation == Surface.ROTATION_90 || surfaceRotation == Surface.ROTATION_270) {
                    Bitmap.createBitmap(image.height, image.width, Bitmap.Config.ARGB_8888)
                } else {
                    Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                }
                buffer.rewind()
                bitmap!!.copyPixelsFromBuffer(buffer)

                my_image.post {
                    my_image.setImageBitmap(bitmap)
                }
                image.close()
            }
        }


    }

    private fun setupAnalyser(): XImageAnalysis {

        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            // Use a worker thread for image analysis to prevent glitches
            setLensFacing((CameraX.LensFacing.FRONT))
            setTargetAspectRatio(Rational(16, 9))
            setTargetResolution(Size(1280, 720))
            val analyzerThread = HandlerThread(
                "CameraAnalyser"
            ).apply { start() }
            setCallbackHandler(Handler(analyzerThread.looper))
        }.build()

        var lastAnalyzedTimestamp = 0L

        // Build the image analysis use case and instantiate our analyzer
        return XImageAnalysis(analyzerConfig).apply {
            setRenderscript(RenderScript.create(this@MainActivity))
            analyzer = XImageAnalysis.Analyzer { image, _ ->

                val currentTimestamp = System.currentTimeMillis()
                // Calculate the average luma no more often than every second
                if (currentTimestamp - lastAnalyzedTimestamp >= 60F) {
                    println(image.image?.planes?.size)
                    val buffer = image.planes[0].buffer
                    val width = image.width
                    val height = image.height
                    println("$width $height")

                    val matrix = Matrix()

                    // Compute the center of the view finder
                    val centerX = viewFinder.width / 2f
                    val centerY = viewFinder.height / 2f

                    // Correct preview output to account for display rotation
                    rotationDegrees = when (viewFinder.display.rotation) {
                        Surface.ROTATION_0 -> 0
                        Surface.ROTATION_90 -> 90
                        Surface.ROTATION_180 -> 180
                        Surface.ROTATION_270 -> 270
                        else -> 0
                    }
                    matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

                    // Finally, apply transformations to our TextureView

                    viewFinder.post {
                        val bm = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bm.copyPixelsFromBuffer(buffer)
//                        bm.rotate(180F)
                        my_image.setImageBitmap(bm)

                    }
//                }

                    lastAnalyzedTimestamp = currentTimestamp
                    image.close()
                }
            }
        }


    }

    fun Bitmap.rotate(degrees: Float) =
        Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees) }, true)

    /**
     * Process result from permission request dialog box, has the request
     * been granted? If yes, start Camera. Otherwise display a toast
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                viewFinder.post { startCamera() }
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    /**
     * Check if all permission specified in the manifest have been granted
     */
    private fun allPermissionsGranted(): Boolean {
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(
                    this, permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }
}
