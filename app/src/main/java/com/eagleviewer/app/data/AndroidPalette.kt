package com.eagleviewer.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.provider.DocumentsContract
import com.eagleviewer.app.data.db.ItemWithTags
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.roundToInt

/** Exact document structure check is pure so hostile/cross-root paths are JVM testable. */
object PaletteSourceScope {
    fun accepts(rootAuthority:String?,sourceAuthority:String?,rootTree:String,sourceTree:String,document:String,id:String):Boolean {
        fun safe(s:String)=s.isNotEmpty() && s!="." && s!=".." && s.none { it=='/' || it=='\\' || it.isISOControl() }
        if(rootAuthority.isNullOrEmpty() || rootAuthority!=sourceAuthority || rootTree!=sourceTree || !safe(id)) return false
        val prefix=rootTree.trimEnd('/')+"/images/"+id+".info/"
        return document.startsWith(prefix) && safe(document.removePrefix(prefix))
    }
}
class SafPaletteCapture(private val context:Context):PaletteCapture {
    override suspend fun capture(root:String,row:ItemWithTags):PaletteSnapshot {
        val tree=Uri.parse(root); val source=Uri.parse(row.item.imageUri)
        require(tree.scheme=="content" && source.scheme=="content" && tree.query==null && source.query==null && tree.fragment==null && source.fragment==null)
        require(PaletteSourceScope.accepts(tree.authority,source.authority,DocumentsContract.getTreeDocumentId(tree),
            DocumentsContract.getTreeDocumentId(source),DocumentsContract.getDocumentId(source),row.item.id))
        return capturePaletteStream(privatePaletteDirectory(context.dataDir,context.cacheDir,"local-palette-snapshots")) {
            context.contentResolver.openInputStream(source) ?: error("source unavailable")
        }
    }
}

/** Decoder buffers are bounded; Android codec internal peak RSS is not guaranteed by these limits. */
class AndroidPaletteDecoder:PaletteDecoder {
    override suspend fun decode(snapshot:File,ext:String):PalettePixels? {
        if(ext.lowercase() !in setOf("png","jpg","jpeg","heic","webp")) return null
        if(ext.equals("webp",true) && !PaletteWebp.isStatic(snapshot)) return null
        currentCoroutineContext().ensureActive()
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(snapshot.path,bounds)
        val width=bounds.outWidth; val height=bounds.outHeight
        if(width<=0 || height<=0 || width.toLong()*height>50_000_000L) return null
        // MIME must agree with a supported still format; do not expand via misleading extension.
        val expected=when(ext.lowercase()) {
            "png" -> setOf("image/png")
            "jpg","jpeg" -> setOf("image/jpeg")
            "heic" -> setOf("image/heic","image/heif")
            "webp" -> setOf("image/webp")
            else -> emptySet()
        }
        if(bounds.outMimeType !in expected) return null
        var sample=1
        while((max(width,height).toLong()+sample-1)/sample>256) sample*=2
        currentCoroutineContext().ensureActive()
        val options=BitmapFactory.Options().apply {
            inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888
            inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB);inScaled=false
        }
        val decoded=BitmapFactory.decodeFile(snapshot.path,options) ?: return null
        var scaled:Bitmap?=null
        try {
            currentCoroutineContext().ensureActive()
            if(decoded.width !in 1..256 || decoded.height !in 1..256 || decoded.config!=Bitmap.Config.ARGB_8888 ||
                decoded.colorSpace!=ColorSpace.get(ColorSpace.Named.SRGB)) return null
            val edge=max(decoded.width,decoded.height)
            val bitmap=if(edge>128) Bitmap.createScaledBitmap(decoded,
                (decoded.width*128.0/edge).roundToInt().coerceAtLeast(1),
                (decoded.height*128.0/edge).roundToInt().coerceAtLeast(1),true).also { scaled=it } else decoded
            currentCoroutineContext().ensureActive()
            if(bitmap.width !in 1..128 || bitmap.height !in 1..128) return null
            val pixels=IntArray(bitmap.width*bitmap.height)
            // Public Bitmap contract: returned packed sRGB ARGB is straight, not premultiplied.
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            currentCoroutineContext().ensureActive()
            return PalettePixels(bitmap.width,bitmap.height,pixels)
        } finally { if(scaled!==decoded) scaled?.recycle();decoded.recycle() }
    }
}

/** Reject explicit animation chunks/flag and malformed RIFF before invoking a platform codec. */
object PaletteWebp {
    fun isStatic(file:File):Boolean=RandomAccessFile(file,"r").use { input ->
        if(input.length()<12) return false
        fun fourcc():String { val bytes=ByteArray(4);input.readFully(bytes);return String(bytes,Charsets.US_ASCII) }
        fun u32():Long { var value=0L;repeat(4) { value=value or (input.readUnsignedByte().toLong() shl (8*it)) };return value }
        if(fourcc()!="RIFF") return false
        val end=u32()+8
        if(fourcc()!="WEBP" || end!=input.length()) return false
        var chunks=0
        while(input.filePointer<end) {
            if(++chunks>4096 || end-input.filePointer<8) return false
            val type=fourcc();val size=u32();val start=input.filePointer
            if(size>end-start || size+(size and 1)>end-start || type=="ANIM" || type=="ANMF") return false
            if(type=="VP8X" && (size<10 || input.readUnsignedByte() and 2 !=0)) return false
            input.seek(start+size+(size and 1))
        }
        true
    }
}
