package com.example.appsl.ui

import android.Manifest
import android.content.ContentValues
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.example.appsl.BoundingBox
import com.example.appsl.Classifier
import com.example.appsl.Constants.LABELS_PATH
import com.example.appsl.Constants.MODEL_PATH
import com.example.appsl.Constants.CLASSIFICATION_MODEL_PATH
import com.example.appsl.Detector
import com.example.appsl.databinding.FragmentCameraBinding
import com.example.appsl.ui.Animation.animateArrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.TensorImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment(), Detector.DetectorListener {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private val isFrontCamera = false
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null
    private var classifier: Classifier? = null
    private var lastAnalyzedBitmap: Bitmap? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = Detector(requireContext(), MODEL_PATH, LABELS_PATH, this) {
                toast(it)
            }
            classifier = Classifier(requireContext(), CLASSIFICATION_MODEL_PATH)

        }

        requestStoragePermission()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(requireActivity(), REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        setBottomSheet()
    }

    private fun requestStoragePermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_CODE_WRITE_STORAGE_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_WRITE_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permesso concesso, possiamo procedere
                toast("Permesso di scrittura concesso")
            } else {
                // Permesso negato, informa l'utente
                toast("Permesso di scrittura negato")
            }
        }
    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider  = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview =  Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            lastAnalyzedBitmap = rotatedBitmap
            detector?.detect(rotatedBitmap)
        }


        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.surfaceProvider = binding.viewFinder.surfaceProvider
        } catch(exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }

    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()){
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_CODE_WRITE_STORAGE_PERMISSION = 101
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).toTypedArray()
    }


    override fun onEmptyDetect() {
        requireActivity().runOnUiThread {
            binding.overlay.clear()
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        requireActivity().runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"

            // for each bounding box we call the classifier
            boundingBoxes.forEach { boundingBox ->
                lastAnalyzedBitmap?.let { bitmap ->
                    // crop the image with bounding box found
                    val croppedBitmap = cropBitmap(bitmap, boundingBox)
                    // call to the CNN classifier
                    //saveBitmapToFile(croppedBitmap, "debug")
                    val classificationResult = classifyImage(croppedBitmap)
                    // class name is stored in bounding box
                    boundingBox.clsName = classificationResult
                }
            }

            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap, fileName: String): String? {

        val resolver = requireContext().contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }

        val imageUri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        Log.d("SaveBitmap", "Salvando...")
        imageUri?.let {
            try {
                val outputStream = resolver.openOutputStream(it)
                outputStream?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                Log.d("SaveBitmap", "Immagine salvata in: ${imageUri.path}")
                toast("Immagine salvata in: ${imageUri.path}")
                return imageUri.path
            } catch (e: IOException) {
                Log.e("SaveBitmap", "Errore durante il salvataggio dell'immagine: ${e.localizedMessage}")
                e.printStackTrace()
            }
        }
        Log.d("SaveBitmap", "Immagine non salvata")
        return null
    }


    private fun cropBitmap(bitmap: Bitmap, boundingBox: BoundingBox): Bitmap {
        //Log.d(TAG, "Bounding box position: left=$boundingBox.x1, top=$boundingBox.y1, right=$boundingBox.x2, bottom=$boundingBox.y2")
        val left = (boundingBox.x1 * bitmap.width).toInt()
        val top = (boundingBox.y1 * bitmap.height).toInt()
        val right = (boundingBox.x2 * bitmap.width).toInt()
        val bottom = (boundingBox.y2 * bitmap.height).toInt()

        // verifies if the bounding box is valid after Int()
        val validLeft = maxOf(0, left)
        val validTop = maxOf(0, top)
        val validRight = minOf(bitmap.width, right)
        val validBottom = minOf(bitmap.height, bottom)

        // crops the image at the bbox
        val croppedBitmap = Bitmap.createBitmap(bitmap, validLeft, validTop, validRight - validLeft, validBottom - validTop)

        return addPaddingToBitmap(croppedBitmap, bitmap, validLeft, validTop, validRight - validLeft, validBottom - validTop)
    }

    private fun addPaddingToBitmap(
        croppedBitmap: Bitmap,
        originalBitmap: Bitmap,
        x1: Int,
        y1: Int,
        croppedWidth: Int,
        croppedHeight: Int
    ): Bitmap {
        val width = croppedBitmap.width
        val height = croppedBitmap.height
        val size = maxOf(width, height)

        // Creare una bitmap nera
        val paddedBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(paddedBitmap)

        val left = (size - width) / 2
        val top = (size - height) / 2

        // Disegnare l'immagine ritagliata centrata nella bitmap nera
        canvas.drawBitmap(croppedBitmap, left.toFloat(), top.toFloat(), null)

        // Non serve altro, il padding sarà automaticamente nero poiché la bitmap di base è nera
        return paddedBitmap
    }


    private fun classifyImage(bitmap: Bitmap): String {
        val INPUT_WIDTH = 200
        val INPUT_HEIGHT = 200
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true)

        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(resizedBitmap)

        // try to classify; if nothing returns -> "Unknown"
        val result = classifier?.classify(tensorImage) ?: "Unknown"

        return result
    }

    private fun toast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    private fun setBottomSheet() {
        val bottomSheetBehavior = BottomSheetBehavior.from(binding.llSheet)

        bottomSheetBehavior.peekHeight = 50
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        animateArrow(binding.ivArrow, 180f, 0f)
                    }
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        animateArrow(binding.ivArrow, 0f, 180f)
                    }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {}
        })

        binding.llSheet.setOnClickListener {
            if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

}

