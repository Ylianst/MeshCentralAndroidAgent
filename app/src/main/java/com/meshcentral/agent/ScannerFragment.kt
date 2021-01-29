package com.meshcentral.agent

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import com.budiyev.android.codescanner.CodeScanner
import com.budiyev.android.codescanner.CodeScannerView
import com.budiyev.android.codescanner.DecodeCallback
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import java.util.jar.Manifest

/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
class ScannerFragment : Fragment(), PermissionListener {
    private var lastToast : Toast? = null
    private lateinit var codeScanner: CodeScanner
    var alert : AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.scanner_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scannerFragment = this;
        visibleScreen = 2;

        view.findViewById<Button>(R.id.button_second).setOnClickListener {
            lastToast?.cancel()
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        }

        val scannerView = view.findViewById<CodeScannerView>(R.id.scanner_view)
        val activity = requireActivity()
        lastToast = Toast.makeText(activity, "", Toast.LENGTH_LONG)
        codeScanner = CodeScanner(activity, scannerView)
        codeScanner.decodeCallback = DecodeCallback {
            activity.runOnUiThread {
                if (isMshStringValid(it.text)) {
                    lastToast?.cancel()
                    confirmServerSetup(it.text)
                } else {
                    lastToast?.setGravity(Gravity.CENTER, 0, 300)
                    lastToast?.setText(getString(R.string.invalid_qrcode))
                    lastToast?.show()
                    codeScanner.startPreview()
                }
            }
        }
        scannerView.setOnClickListener {
            codeScanner.startPreview()
        }
    }

    override fun onDestroy() {
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        lastToast?.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        println("onResume")
        super.onResume()
        Dexter.withContext(context)
            .withPermission(android.Manifest.permission.CAMERA)
            .withListener(this)
            .check()
        //codeScanner.startPreview()
    }

    override fun onPause() {
        codeScanner.releaseResources()
        super.onPause()
    }

    fun getServerHost(serverLink : String?) : String? {
        if (serverLink == null) return null
        var x : List<String> = serverLink.split(',')
        var serverHost = x[0]
        return serverHost.substring(5)
    }

    fun confirmServerSetup(x:String) {
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        val builder = AlertDialog.Builder(activity)
        builder.setTitle("MeshCentral Server")
        builder.setMessage("Setup to: ${getServerHost(x)}?")
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            visibleScreen = 1
            (activity as MainActivity).setMeshServerLink(x)
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        }
        builder.setNeutralButton(android.R.string.cancel) { _, _ ->
            codeScanner.startPreview()
        }
        alert = builder.show()
    }

    override fun onPermissionGranted(p0: PermissionGrantedResponse?) {
        println("onPermissionGranted")
        codeScanner.startPreview()
    }

    override fun onPermissionRationaleShouldBeShown(p0: PermissionRequest?, p1: PermissionToken?) {
        println("onPermissionRationaleShouldBeShown")
        p1?.continuePermissionRequest()
    }

    override fun onPermissionDenied(p0: PermissionDeniedResponse?) {
        println("onPermissionDenied")
        findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
    }

    fun exit() {
        findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
    }

    fun isMshStringValid(x:String):Boolean {
        if (x.startsWith("mc://") == false)  return false
        var xs = x.split(',')
        if (xs.count() < 3) return false
        if (xs[0].length < 8) return false
        if (xs[1].length < 3) return false
        if (xs[2].length < 3) return false
        if (xs[0].indexOf('.') == -1) return false
        return true
    }
}