package com.meshcentral.agent

import android.Manifest
import android.R.attr.*
import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener


/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class MainFragment : Fragment(), MultiplePermissionsListener {
    var alert : AlertDialog? = null

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.main_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mainFragment = this
        visibleScreen = 1;

        refreshInfo()

        view.findViewById<Button>(R.id.agentActionButton).setOnClickListener {
            var serverLink = serverLink;
            if (serverLink == null) {
                // Setup the server
                if (cameraPresent) {
                    findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
                } else {
                    g_mainActivity!!.promptForServerLink()
                }
            } else {
                if ((activity as MainActivity).isAgentDisconnected() == false) {
                    (activity as MainActivity).toggleAgentConnection(true)
                } else {
                    // Perform action on the agent
                    Dexter.withContext(context)
                        .withPermissions(
                                //Manifest.permission.CAMERA,
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                        .withListener(this)
                        .check()
                }
            }
        }

        // Check if the app was called using a URL link
        if ((activity != null) && ((activity as MainActivity).intent != null) && ((activity as MainActivity).intent.data != null)) {
            var data: Uri? = (activity as MainActivity).intent.data;
            if (data != null && data.isHierarchical()) {
                var uri: String? = (activity as MainActivity).intent.dataString;
                if ((uri != null) && (isMshStringValid(uri))) {
                    confirmServerSetup(uri)
                }
            }
        }
    }

    fun isMshStringValid(x: String):Boolean {
        if (x.startsWith("mc://") == false)  return false
        var xs = x.split(',')
        if (xs.count() < 3) return false
        if (xs[0].length < 8) return false
        if (xs[1].length < 3) return false
        if (xs[2].length < 3) return false
        if (xs[0].indexOf('.') == -1) return false
        return true
    }

    fun moveToScanner() {
        println("moveToScanner $visibleScreen")
        if (visibleScreen == 1) { findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment) }
    }

    fun moveToWebPage(pageUrl: String) {
        println("moveToWebPage $visibleScreen")
        if (visibleScreen == 1) { findNavController().navigate(R.id.action_FirstFragment_to_webViewFragment) }
    }

    fun moveToAuthPage() {
        println("moveToAuthPage $visibleScreen")
        if (visibleScreen == 1) { findNavController().navigate(R.id.action_FirstFragment_to_authFragment) }
    }

    fun moveToSettingsPage() {
        println("moveToSettingsPage $visibleScreen")
        if (visibleScreen == 1) { findNavController().navigate(R.id.action_FirstFragment_to_settingsFragment) }
    }

    private fun getStringEx(resId: Int) : String {
        try { return getString(resId); } catch (ex: Exception) {}
        return "";
    }

    fun refreshInfo() {
        var showServerTitle : String? = null
        var showServerLogo : Int = 0 // 0 = Default, 1 = User, 2 = Users, 3 = Custom
        view?.findViewById<TextView>(R.id.serverNameTextView)?.text = getServerHost(serverLink)
        if (serverLink == null) {
            // Server not setup
            view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 0.4F
            view?.findViewById<TextView>(R.id.agentStatusTextview)?.text = getStringEx(R.string.no_server_setup)
            view?.findViewById<TextView>(R.id.agentActionButton)?.text = getStringEx(R.string.setup_server)
            //view?.findViewById<TextView>(R.id.agentActionButton)?.isEnabled = cameraPresent
            if (visibleScreen == 4) {
                authFragment?.exit()
            }
        } else {
            // Server is setup, display state of the agent
            var state: Int = 0;
            if (meshAgent != null) {
                state = meshAgent!!.state;
            }
            view?.findViewById<TextView>(R.id.agentActionButton)?.isEnabled = true
            if (state == 0) {
                if (g_retryTimer != null) {
                    // Trying to connect
                    view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 0.5F
                    view?.findViewById<TextView>(R.id.agentStatusTextview)?.text =
                            getStringEx(R.string.connecting)
                    view?.findViewById<TextView>(R.id.agentActionButton)?.text =
                            getStringEx(R.string.disconnect)
                } else {
                    // Disconnected
                    view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 0.5F
                    view?.findViewById<TextView>(R.id.agentStatusTextview)?.text =
                            getStringEx(R.string.disconnected)
                    view?.findViewById<TextView>(R.id.agentActionButton)?.text =
                            getStringEx(R.string.connect)
                }
                if (visibleScreen == 4) {
                    authFragment?.exit()
                }
            } else if (state == 1) {
                // Connecting
                view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 0.5F
                view?.findViewById<TextView>(R.id.agentStatusTextview)?.text =
                        getStringEx(R.string.connecting)
                view?.findViewById<TextView>(R.id.agentActionButton)?.text =
                        getStringEx(R.string.disconnect)
                if (visibleScreen == 4) {
                    authFragment?.exit()
                }
            } else if (state == 2) {
                // Verifying
                view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 0.5F
                view?.findViewById<TextView>(R.id.agentStatusTextview)?.text =
                        getStringEx(R.string.authenticating)
                view?.findViewById<TextView>(R.id.agentActionButton)?.text =
                        getStringEx(R.string.disconnect)
                if (visibleScreen == 4) {
                    authFragment?.exit()
                }
            } else if (state == 3) {
                // Connected
                view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 1.0F
                view?.findViewById<TextView>(R.id.agentStatusTextview)?.text =
                        getStringEx(R.string.connected)
                view?.findViewById<TextView>(R.id.agentActionButton)?.text =
                        getStringEx(R.string.disconnect)

                // Get a list of userid's with active tunnels
                val userSessions : ArrayList<String> = ArrayList()
                for (tunnel in meshAgent!!.tunnels) {
                    try {
                        if ((tunnel.sessionUserName2 != null) && (!userSessions.contains(tunnel.sessionUserName2))) {
                            userSessions.add(tunnel.sessionUserName2!!)
                        }
                    } catch (ex: Exception) {}
                }

                // Set the application title
                if (meshAgent?.serverTitle != null) { showServerTitle = meshAgent!!.serverTitle; }

                // Set the title
                var serverNameTitle : String? = getServerHost(serverLink)
                var serverImage : Bitmap? = null
                try {
                    if (userSessions.size > 1) {
                        serverNameTitle = "${userSessions.size} users have sessions."
                    } else if (userSessions.size == 1) {
                        val useridsplit: List<String> = userSessions[0].split("/");
                        val userid = useridsplit[0] + "/" + useridsplit[1] + "/" + useridsplit[2]
                        var guestname : String = ""
                        if (useridsplit.size == 4)  { guestname = " - " + useridsplit[3] }
                        serverImage = meshAgent!!.userinfo[userid]!!.image
                        if (meshAgent!!.userinfo[userid]!!.realname != null) {
                            serverNameTitle = meshAgent!!.userinfo[userid]!!.realname + guestname
                        } else {
                            serverNameTitle = userid.split("/")[2] + guestname
                        }
                    }
                } catch (ex: Exception) { }
                view?.findViewById<TextView>(R.id.serverNameTextView)?.text = serverNameTitle

                // Setup the server image
                if (serverImage != null) {
                    // Display user account image
                    val imageView = view?.findViewById<ImageView>(R.id.mainImageView)
                    imageView?.setImageBitmap(serverImage)
                    val param = imageView?.layoutParams as ViewGroup.MarginLayoutParams
                    param.setMargins(128, 128, 128, 128)
                    imageView.layoutParams = param
                    showServerLogo = 3
                } else {
                    if (userSessions.size == 0) {
                        if (meshAgent?.serverImage != null) {
                            // Display branded server logo
                            val imageView = view?.findViewById<ImageView>(R.id.mainImageView)
                            if (imageView != null) {
                                imageView.setImageBitmap(meshAgent?.serverImage)
                                try {
                                    val param = imageView.layoutParams as ViewGroup.MarginLayoutParams?
                                    if (param != null) {
                                        param.setMargins(128, 128, 128, 128)
                                        imageView.layoutParams = param
                                    }
                                } catch (ex: Exception) {}
                            }
                            showServerLogo = 3
                        }
                    } else if (userSessions.size == 1) {
                        // Display single user default image
                        showServerLogo = 1
                    } else if (userSessions.size > 1) {
                        // Display multi user default image
                        showServerLogo = 2
                    }
                }

                // If we are connected and 2FA was requested, switch to 2FA screen
                if ((g_auth_url != null) && (visibleScreen != 4)) {
                    moveToAuthPage()
                }
            }
        }

        // Update application title
        var toolbar =
            g_mainActivity?.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        if (showServerTitle != null) {
            toolbar?.title = showServerTitle
            getActivity()?.setTitle(showServerTitle);
            if (meshAgent?.serverSubTitle != null) { toolbar?.subtitle = meshAgent!!.serverSubTitle; }
        } else {
            toolbar?.title = getStringEx(R.string.app_name)
            toolbar?.subtitle = null
            getActivity()?.setTitle(R.string.app_name);
        }

        if (showServerLogo == 0) {
            // Display default MeshCentral image
            var imageView : ImageView? = null
            try { imageView = view?.findViewById<ImageView>(R.id.mainImageView) } catch (ex: Exception) {}
            if (imageView != null) {
                imageView?.setImageResource(R.mipmap.ic_launcher_foreground)
                val param = imageView?.layoutParams
                if (param is ViewGroup.MarginLayoutParams) {
                    (param as ViewGroup.MarginLayoutParams).setMargins(0, 0, 0, 0)
                    imageView?.layoutParams = param
                }
            }
        }
        else if ((showServerLogo == 1) || (showServerLogo == 2)) {
            // Display single user default image
            var imageView : ImageView? = null
            try { imageView = view?.findViewById<ImageView>(R.id.mainImageView) } catch (ex: Exception) {}
            if (imageView != null) {
                imageView?.setImageResource(R.mipmap.ic_user)
                val param = imageView?.layoutParams
                if (param is ViewGroup.MarginLayoutParams) {
                    (param as ViewGroup.MarginLayoutParams).setMargins(128, 128, 128, 128)
                    imageView?.layoutParams = param
                }
            }
        }
        /*
        else if (showServerLogo == 2) {
            // Display multi user default image
            val imageView = view?.findViewById<ImageView>(R.id.mainImageView)
            imageView?.setImageResource(R.mipmap.ic_users)
            val param = imageView?.layoutParams
            if (param is ViewGroup.MarginLayoutParams) {
                (param as ViewGroup.MarginLayoutParams).setMargins(128, 128, 128, 128)
                imageView?.layoutParams = param
            }
        }
        */
    }

    fun getServerHost(serverLink: String?) : String? {
        if (serverLink == null) return null
        var x : List<String> = serverLink.split(',')
        var serverHost = x[0]
        serverHost = serverHost.substring(5) // Remove the mc://
        var i = serverHost.indexOf(':')
        if (i > 0) { serverHost = serverHost.substring(0, i) } // Remove the port number if present
        return serverHost
    }

    override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
        println("onPermissionsChecked")
        (activity as MainActivity).toggleAgentConnection(false)
    }

    override fun onPermissionRationaleShouldBeShown(permissions: MutableList<PermissionRequest>?, token: PermissionToken?) {
        println("onPermissionRationaleShouldBeShown")
        token?.continuePermissionRequest()
    }

    fun confirmServerSetup(x: String) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle("MeshCentral Server")
        builder.setMessage("Setup to: ${getServerHost(x)}?")
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            (activity as MainActivity).setMeshServerLink(x)
            (activity as MainActivity).intent.removeExtra("key");
            (activity as MainActivity).intent.action = "";
            (activity as MainActivity).intent.data = null;
        }
        builder.setNeutralButton(android.R.string.cancel) { _, _ ->
            (activity as MainActivity).intent.removeExtra("key");
            (activity as MainActivity).intent.action = "";
            (activity as MainActivity).intent.data = null;
        }
        alert = builder.show()
    }

    override fun onDestroy() {
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        super.onDestroy()
    }
}