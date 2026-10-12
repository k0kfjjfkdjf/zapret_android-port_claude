package io.github.zapretdroid.core

import android.content.Context
import java.io.File

/** Host lists referenced from arguments as {list:name}. */
object HostLists {
    fun dir(context: Context): File = File(context.filesDir, "lists").apply { mkdirs() }
}
