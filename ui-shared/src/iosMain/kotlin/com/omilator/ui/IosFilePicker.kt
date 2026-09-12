@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.omilator.ui

import platform.Foundation.NSURL
import platform.UIKit.UIViewController
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import kotlin.native.concurrent.ThreadLocal

private class PickerDelegate(
    private val onPicked: (String?) -> Unit
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
            ?: return onPicked(null)
        // Outside the sandbox the URL is only readable inside a
        // security-scoped access window. Copy the item into Documents so
        // the returned path keeps working after this callback returns —
        // a bare url?.path can lose access the moment the picker closes.
        val accessing = url.startAccessingSecurityScopedResource()
        try {
            val fm = platform.Foundation.NSFileManager.defaultManager
            val docs = (platform.Foundation.NSSearchPathForDirectoriesInDomains(
                platform.Foundation.NSDocumentDirectory,
                platform.Foundation.NSUserDomainMask,
                true,
            ).firstOrNull() as? String) ?: return onPicked(url.path)
            val dest = "$docs/${url.lastPathComponent}"
            if (fm.fileExistsAtPath(dest)) {
                fm.removeItemAtPath(dest, null)
            }
            val copied = fm.copyItemAtPath(url.path ?: "", toPath = dest, error = null)
            onPicked(if (copied) dest else url.path)
        } finally {
            if (accessing) url.stopAccessingSecurityScopedResource()
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onPicked(null)
    }
}

@ThreadLocal
private var retainedDelegate: PickerDelegate? = null

fun pickFile(viewController: UIViewController?, onPicked: (String?) -> Unit) {
    val vc = viewController ?: return
    val contentTypes: List<UTType> = listOfNotNull(UTType.typeWithIdentifier("public.item"))
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = contentTypes)
    picker.allowsMultipleSelection = false
    val delegate = PickerDelegate(onPicked)
    retainedDelegate = delegate
    picker.delegate = delegate
    vc.presentViewController(picker, true, null)
}

fun pickDirectory(viewController: UIViewController?, onPicked: (String?) -> Unit) {
    val vc = viewController ?: return
    val contentTypes: List<UTType> = listOfNotNull(UTType.typeWithIdentifier("public.folder"))
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = contentTypes)
    picker.allowsMultipleSelection = false
    val delegate = PickerDelegate(onPicked)
    retainedDelegate = delegate
    picker.delegate = delegate
    vc.presentViewController(picker, true, null)
}
