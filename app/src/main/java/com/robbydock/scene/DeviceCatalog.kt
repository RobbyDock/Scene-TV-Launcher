package com.robbydock.scene

import android.os.Build

/** Known streaming devices, by a friendly name. */
object DeviceCatalog {
    /** The best name for this device, or the one Android reports. */
    fun name(): String {
        val model = Build.MODEL.orEmpty()
        val maker = Build.MANUFACTURER.orEmpty()
        if (model.contains("onn.", true) && model.contains("Box", true)) return "onn. 4K Streaming Box"
        if (model.contains("onn.", true)) return model
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }
}
