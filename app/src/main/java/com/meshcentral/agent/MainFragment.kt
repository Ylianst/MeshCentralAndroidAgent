package com.meshcentral.agent

import android.Manifest
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import com.karumi.dexter.listener.single.PermissionListener

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class MainFragment : Fragment(), MultiplePermissionsListener {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        (activity as MainActivity).mainFragment = this
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.main_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        refreshInfo()

        view.findViewById<Button>(R.id.agentActionButton).setOnClickListener {
            var serverLink = (activity as MainActivity).serverLink;
            if (serverLink == null) {
                // Setup the server
                (activity as MainActivity).visibleScreen = 2;
                findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
            } else {
                if ((activity as MainActivity).isAgentDisconnected() == false) {
                    (activity as MainActivity).toggleAgentConnection()
                } else {
                    // Perform action on the agent
                    Dexter.withContext(context)
                        .withPermissions(
                            Manifest.permission.CAMERA,
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                        .withListener(this)
                        .check()
                }
            }
        }
    }

    fun moveToScanner() {
        (activity as MainActivity).visibleScreen = 2;
        findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
    }

    fun refreshInfo() {
        var serverLink = (activity as MainActivity).serverLink;
        view?.findViewById<TextView>(R.id.serverNameTextView)?.text = getServerHost(serverLink)
        if (serverLink == null) {
            // Server not setup
            view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 0.4F
            view?.findViewById<TextView>(R.id.agentStatusTextview)?.text = getString(R.string.no_server_setup)
            view?.findViewById<TextView>(R.id.agentActionButton)?.text = getString(R.string.setup_server)
        } else {
            // Server is setup, display state of the agent
            val state = (activity as MainActivity).meshAgent?.state;
            if ((state == 0) || (state == null)) {
                // Disconnected
                view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 0.5F
                view?.findViewById<TextView>(R.id.agentStatusTextview)?.text =
                    getString(R.string.disconnected)
                view?.findViewById<TextView>(R.id.agentActionButton)?.text =
                    getString(R.string.connect)
            } else if (state == 1) {
                // Connecting
                view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 0.5F
                view?.findViewById<TextView>(R.id.agentStatusTextview)?.text =
                    getString(R.string.connecting)
                view?.findViewById<TextView>(R.id.agentActionButton)?.text =
                    getString(R.string.disconnect)
            } else if (state == 2) {
                // Verifying
                view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 0.5F
                view?.findViewById<TextView>(R.id.agentStatusTextview)?.text =
                    getString(R.string.authenticating)
                view?.findViewById<TextView>(R.id.agentActionButton)?.text =
                    getString(R.string.disconnect)
            } else if (state == 3) {
                // Connected
                view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 1.0F
                view?.findViewById<TextView>(R.id.agentStatusTextview)?.text =
                        getString(R.string.connected)
                view?.findViewById<TextView>(R.id.agentActionButton)?.text =
                        getString(R.string.disconnect)
            }
        }
    }

    fun getServerHost(serverLink : String?) : String? {
        if (serverLink == null) return null
        var x : List<String> = serverLink.split(',')
        var serverHost = x[0]
        return serverHost.substring(5)
    }

    override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
        println("onPermissionsChecked")
        (activity as MainActivity).toggleAgentConnection()
    }

    override fun onPermissionRationaleShouldBeShown(permissions: MutableList<PermissionRequest>?, token: PermissionToken?) {
        println("onPermissionRationaleShouldBeShown")
        token?.continuePermissionRequest()
    }

}