package com.meshcentral.agent

import android.net.http.SslError
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import androidx.navigation.fragment.findNavController

/**
 * A simple [Fragment] subclass.
 * Use the [WebViewFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class WebViewFragment : Fragment() {
    var browser : WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.webview_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        webFragment = this
        visibleScreen = 3;
        browser = view.findViewById(R.id.mainWebView) as WebView
        browser?.settings?.javaScriptEnabled = true
        browser?.webViewClient = object : WebViewClient(){
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                url: String?
            ): Boolean {
                //println("shouldOverrideUrlLoading: $url")
                pageUrl = url;
                view?.loadUrl(url!!)
                return true
            }

            /*
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
                // Ignore TLS certificate errors and instruct the WebViewClient to load the website
                println("onReceivedSslError: $error")
                handler.proceed();
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                println("onLoadResource: $url")
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                println("onReceivedError: $error")
            }

            override fun onSafeBrowsingHit(view: WebView?, request: WebResourceRequest?, threatType: Int, callback: SafeBrowsingResponse?) {
                super.onSafeBrowsingHit(view, request, threatType, callback)
                println("onSafeBrowsingHit: $threatType")
            }
            */
        }
        browser?.loadUrl(pageUrl!!)
    }

    fun navigate(url: String) {
        browser?.loadUrl(url)
    }

    fun exit() {
        browser?.loadUrl("")
        pageUrl = null
        findNavController().navigate(R.id.action_webViewFragment_to_FirstFragment)
    }
}