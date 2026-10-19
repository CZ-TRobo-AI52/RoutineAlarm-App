package com.cztr.routinealarm.support

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Wait for the real worker response; advancing Robolectric's main queue alone is insufficient. */
fun awaitUi(activity: Activity) {
    val pending = setOf("Teststatus wird geladen …", "Testverlauf wird geladen …", "Testwecker wird geprüft …")
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        if (labels(activity).none { it in pending }) return
        Thread.sleep(5)
    }
    throw AssertionError("UI read did not finish; visible labels: ${labels(activity)}")
}

/** Call after Activity destruction and before deleting its database. Reflection stays test-only. */
fun awaitUiReadersStopped(activity: Activity) {
    val fields = activity.javaClass.declaredFields.filter { it.type.simpleName == "LatestUiLoader" }
    check(fields.isNotEmpty()) { "No UI reader found on ${activity.javaClass.simpleName}" }
    fields.forEach { field ->
        field.isAccessible = true
        val loader = requireNotNull(field.get(activity))
        val workerField = loader.javaClass.declaredFields.single {
            ExecutorService::class.java.isAssignableFrom(it.type)
        }
        workerField.isAccessible = true
        val worker = workerField.get(loader) as ExecutorService
        assertTrue("UI reader did not stop; labels: ${labels(activity)}",
            worker.awaitTermination(10, TimeUnit.SECONDS))
    }
    shadowOf(Looper.getMainLooper()).idle()
}

private fun labels(activity: Activity): List<String> = views(activity.window.decorView)
    .filterIsInstance<TextView>().map { it.text.toString() }

private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
    (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
