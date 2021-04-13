package com.meshcentral.agent

import android.os.Bundle
import android.os.CountDownTimer
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.navigation.fragment.findNavController

class AuthFragment : Fragment() {
    var countDownTimer : CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_auth, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        authFragment = this
        visibleScreen = 4;

        // Set authentication code
        var t:TextView = view.findViewById<Button>(R.id.authTopText2) as TextView
        if (g_auth_code != null) { t.text = g_auth_code }

        // Set authentication progress bar
        var p:ProgressBar = view.findViewById<Button>(R.id.authProgressBar) as ProgressBar
        p.progress = 100
        countDownTimer = object : CountDownTimer(60000, 600) {
            override fun onTick(millisUntilFinished: Long) {
                var p:ProgressBar = view.findViewById<Button>(R.id.authProgressBar) as ProgressBar
                if (p.progress > 0) { p.progress = p.progress - 1 }
            }
            override fun onFinish() {
                countDownTimer = null
                exit()
            }
        }.start()

        view.findViewById<Button>(R.id.authAcceptButton).setOnClickListener {
            exit()
        }

        view.findViewById<Button>(R.id.authRejectButton).setOnClickListener {
            exit()
        }
    }

    fun exit() {
        if (countDownTimer != null) {
            countDownTimer?.cancel()
            countDownTimer = null
        }
        findNavController().navigate(R.id.action_authFragment_to_FirstFragment)
    }
}