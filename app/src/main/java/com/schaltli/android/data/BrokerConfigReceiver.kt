package com.schaltli.android.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.schaltli.android.mqtt.BrokerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Sets the broker from a computer over USB, the way POST /api/mqtt does on a
 * board:
 *
 *   adb shell am broadcast -n com.schaltli.android/.data.BrokerConfigReceiver \
 *     -a com.schaltli.android.SET_BROKER --es host 192.168.8.107 --ei port 1883
 *
 * (`--es username` and `--es password` when the broker wants them.) The
 * designer's hil/boards-network.js uses it to move this phone to the camper's
 * broker and back along with the boards.
 *
 * `--ei displayOffSeconds N` sets how long the panel waits before it goes
 * black (ScreenSleep; 0 keeps it on), with or without a host. The broadcast's
 * result data is the value it had before, so a caller can put it back: the
 * android HIL turns the sleep off for its run, because a fixture photographed
 * after a minute without a touch came out all black (2026-09-28).
 *
 * Only adb can send it: the manifest guards the receiver with
 * android.permission.DUMP, which the shell holds and no installed app can be
 * granted. DdfServer stays what it is - a port on the network that hands out
 * a description and takes nothing.
 *
 * The app follows the stored config as a flow, so saving it is enough: the
 * MQTT connection moves to the new broker on its own.
 */
class BrokerConfigReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.schaltli.android.SET_BROKER"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val host = intent.getStringExtra("host")
        val displayOff = if (intent.hasExtra("displayOffSeconds")) intent.getIntExtra("displayOffSeconds", 0) else null
        if (host.isNullOrBlank() && displayOff == null) {
            Log.w("BrokerConfigReceiver", "no host given - broker left as it is")
            return
        }
        val config = host?.takeIf { it.isNotBlank() }?.let {
            BrokerConfig(
                host = it,
                port = intent.getIntExtra("port", 1883),
                username = intent.getStringExtra("username") ?: "",
                password = intent.getStringExtra("password") ?: "",
            )
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = BrokerConfigStore(context.applicationContext)
                if (config != null) {
                    store.save(config)
                    Log.i("BrokerConfigReceiver", "broker set to ${config.host}:${config.port}")
                }
                if (displayOff != null) {
                    pending.resultData = store.displayOffSeconds.first().toString()
                    store.saveDisplayOffSeconds(displayOff)
                    Log.i("BrokerConfigReceiver", "display off after ${displayOff}s")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
