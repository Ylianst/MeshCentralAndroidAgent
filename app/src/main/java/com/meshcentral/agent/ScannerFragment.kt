package com.meshcentral.agent

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Server-pairing QR scanner. Reworked from the deprecated Camera1-based code-scanner (which
 * showed a preview but never decoded on Android 13+/14+) to CameraX + ML Kit, the current,
 * reliable barcode stack. Accepts only the MeshCentral mobile-agent link (mc://...),
 * validated by isMeshServerLinkValid() exactly as before.
 */
class ScannerFragment : Fragment(), PermissionListener {
    private var lastToast: Toast? = null
    var alert: AlertDialog? = null

    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    @Volatile private var handled = false

    private val barcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.scanner_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scannerFragment = this
        visibleScreen = 2
        handled = false
        analysisExecutor = Executors.newSingleThreadExecutor()
        previewView = view.findViewById(R.id.preview_view)
        lastToast = Toast.makeText(requireActivity(), "", Toast.LENGTH_LONG)

        view.findViewById<Button>(R.id.button_second).setOnClickListener {
            lastToast?.cancel()
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        }
    }

    override fun onResume() {
        super.onResume()
        // Ask for camera permission (Dexter); onPermissionGranted starts the camera.
        Dexter.withContext(context)
            .withPermission(android.Manifest.permission.CAMERA)
            .withListener(this)
            .check()
    }

    override fun onPause() {
        stopCamera()
        super.onPause()
    }

    override fun onDestroyView() {
        stopCamera()
        analysisExecutor?.shutdown()
        analysisExecutor = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        if (scannerFragment === this) scannerFragment = null
        alert?.dismiss()
        alert = null
        lastToast?.cancel()
        super.onDestroy()
    }

    // ---- CameraX ---------------------------------------------------------------------

    private fun startCamera() {
        val ctx = context ?: return
        val exec = analysisExecutor ?: return
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(exec, ::analyze) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start camera", e)
                activity?.runOnUiThread { showCameraError() }
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun stopCamera() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(imageProxy: ImageProxy) {
        val media = imageProxy.image
        if (media == null || handled) { imageProxy.close(); return }
        val input = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(input)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull { it.rawValue != null }?.rawValue?.let { onQrText(it) }
            }
            .addOnFailureListener { Log.w(TAG, "barcode decode failed: ${it.message}") }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun onQrText(text: String) {
        val act = activity ?: return
        act.runOnUiThread {
            if (handled) return@runOnUiThread
            if (isMeshServerLinkValid(text)) {
                handled = true
                lastToast?.cancel()
                stopCamera()
                confirmServerSetup(text)
            } else {
                lastToast?.setGravity(Gravity.CENTER, 0, 300)
                lastToast?.setText(getString(R.string.invalid_qrcode))
                lastToast?.show()
            }
        }
    }

    // ---- Dialogs (unchanged behavior) ------------------------------------------------

    private fun showCameraError() {
        if (!isAdded || view == null || alert != null) return
        val activity = activity as? MainActivity ?: return
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(R.string.camera_unavailable)
        builder.setMessage(R.string.camera_unavailable_message)
        builder.setPositiveButton(R.string.retry) { _, _ -> alert = null; startCamera() }
        builder.setNeutralButton(R.string.manual_setup_server) { _, _ ->
            alert = null
            stopCamera()
            visibleScreen = 1
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
            activity.window.decorView.post { activity.promptForServerLink() }
        }
        builder.setNegativeButton(android.R.string.cancel) { _, _ -> alert = null }
        builder.setOnDismissListener { alert = null }
        alert = builder.show()
    }

    fun getServerHost(serverLink: String?): String? {
        if (serverLink == null) return null
        val x: List<String> = serverLink.split(',')
        return x[0].substring(5) // strip "mc://"
    }

    fun confirmServerSetup(x: String) {
        alert?.dismiss()
        alert = null
        val builder = AlertDialog.Builder(activity)
        builder.setTitle("MeshCentral Server")
        builder.setMessage(getString(R.string.setup_message, getServerHost(x)))
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            visibleScreen = 1
            (activity as MainActivity).setMeshServerLink(x)
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        }
        builder.setNeutralButton(android.R.string.cancel) { _, _ ->
            handled = false        // allow scanning again
            startCamera()
        }
        alert = builder.show()
    }

    override fun onPermissionGranted(p0: PermissionGrantedResponse?) { startCamera() }

    override fun onPermissionRationaleShouldBeShown(p0: PermissionRequest?, p1: PermissionToken?) {
        p1?.continuePermissionRequest()
    }

    override fun onPermissionDenied(p0: PermissionDeniedResponse?) {
        findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
    }

    fun exit() { findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment) }

    companion object {
        private const val TAG = "ScannerFragment"
    }
}
