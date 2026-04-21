package com.aichatsdk.demo.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.aichatsdk.models.ChatAttachment
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** 1024px max-edge, 70% JPEG, base64 — matches iOS Demo. */
object AttachmentSupport {

    fun uriToAttachment(context: Context, uri: Uri): ChatAttachment? {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val maxEdge = 1024
        val sample = max(1, min(opts.outWidth, opts.outHeight) / maxEdge)
        val decode = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode) ?: return null
        val resized = scaleToMaxEdge(bmp, maxEdge)
        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return ChatAttachment(kind = "image", data = b64, mimeType = "image/jpeg")
    }

    private fun scaleToMaxEdge(bmp: Bitmap, maxEdge: Int): Bitmap {
        val maxDim = max(bmp.width, bmp.height)
        if (maxDim <= maxEdge) return bmp
        val scale = maxEdge.toFloat() / maxDim
        return Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
    }
}
