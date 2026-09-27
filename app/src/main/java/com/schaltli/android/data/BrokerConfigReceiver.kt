package com.schaltli.android.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.schaltli.android.mqtt.BrokerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        if (host.isNullOrBlank()) {
            Log.w("BrokerConfigReceiver", "no host given - broker left as it is")
            return
        }
        val config = BrokerConfig(
            host = host,
            port = intent.getIntExtra("port", 1883),
            username = intent.getStringExtra("username") ?: "",
            password = intent.getStringExtra("password") ?: "",
        )
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                BrokerConfigStore(context.applicationContext).save(config)
                Log.i("BrokerConfigReceiver", "broker set to ${config.host}:${config.port}")
            } finally {
                pending.finish()
            }
        }
    }
}
