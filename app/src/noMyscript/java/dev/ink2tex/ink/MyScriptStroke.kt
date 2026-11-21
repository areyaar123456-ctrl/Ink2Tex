package dev.ink2tex.ink

import android.content.Context

internal object MyScriptStroke {
    fun available(context: Context): Boolean = context.assets.list("myscript")?.contains("license.bin") == true &&
        dev.ink2tex.BuildConfig.MYSCRIPT_PILOT

    fun open(context: Context): StrokeRecognizer = error(
        "MyScript iink needs the licensed pilot build for ${context.packageName}."
    )
}
