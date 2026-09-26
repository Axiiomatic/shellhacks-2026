package com.example.objectdetection

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(cameraExecutor)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

data class BoxInfo(
    val rect: Rect,
    val label: String,
    val trackingId: Int?
)

@Composable
fun MainScreen(cameraExecutor: ExecutorService) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        CameraPreviewScreen(cameraExecutor)
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Camera permission is required for object detection.")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text(text = "Grant Permission")
                }
            }
        }
    }
}

@Composable
fun CameraPreviewScreen(cameraExecutor: ExecutorService) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var detectedObjects by remember { mutableStateOf<List<BoxInfo>>(emptyList()) }
    var imageWidth by remember { mutableStateOf(1f) }
    var imageHeight by remember { mutableStateOf(1f) }

    // Initialize ML Kit Object Detector
    val objectDetector = remember {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        ObjectDetection.getClient(options)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(
                            imageProxy = imageProxy,
                            detector = objectDetector,
                            onResults = { results, w, h ->
                                detectedObjects = results
                                imageWidth = w
                                imageHeight = h
                            }
                        )
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (exc: Exception) {
                        Log.e("CameraPreview", "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay for bounding boxes
        Canvas(modifier = Modifier.fillMaxSize()) {
            val viewWidth = size.width
            val viewHeight = size.height

            val scaleX = viewWidth / imageHeight
            val scaleY = viewHeight / imageWidth

            for (box in detectedObjects) {
                val rect = box.rect
                val left = rect.top * scaleX
                val top = rect.left * scaleY
                val right = rect.bottom * scaleX
                val bottom = rect.right * scaleY

                drawRect(
                    color = Color.Green,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(width = 4.dp.toPx())
                )
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    imageProxy: ImageProxy,
    detector: com.google.mlkit.vision.objects.ObjectDetector,
    onResults: (List<BoxInfo>, Float, Float) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val imageRotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, imageRotation)

        val width = inputImage.width.toFloat()
        val height = inputImage.height.toFloat()

        detector.process(inputImage)
            .addOnSuccessListener { objects ->
                val list = objects.map { obj ->
                    val labelText = obj.labels.maxByOrNull { it.confidence }?.text ?: "Unknown"
                    BoxInfo(
                        rect = obj.boundingBox,
                        label = labelText,
                        trackingId = obj.trackingId
                    )
                }
                onResults(list, width, height)
            }
            .addOnFailureListener { e ->
                Log.e("ObjectDetection", "Detection failed", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}
