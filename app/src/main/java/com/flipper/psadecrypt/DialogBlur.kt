package com.flipper.psadecrypt

import android.app.Dialog
import android.os.Build
import android.view.WindowManager

fun Dialog.applyBlurBehind() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            attributes = attributes.also { it.blurBehindRadius = 20 }
        }
    }
}
