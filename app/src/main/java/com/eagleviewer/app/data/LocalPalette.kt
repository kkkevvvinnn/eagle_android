package com.eagleviewer.app.data

import com.eagleviewer.app.data.db.ItemWithTags
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.abs

/** Android preprocessing differs from ImageIO: no EXIF transform; platform ICC and bilinear scaling. */
const val LOCAL_PALETTE_ALGORITHM = "android-bitmapfactory-srgb-argb8888-noexif-pow2-256-bilinear128-straight-mmcq5-v1"
const val PALETTE_SOURCE_LIMIT = 512L * 1024 * 1024
fun paletteSha(bytes:ByteArray):String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

data class PaletteIdentity(val root:String,val source:String,val algorithm:String=LOCAL_PALETTE_ALGORITHM) {
    val filename:String get()=paletteSha(buildJsonArray { add(root);add(source);add(algorithm) }.toString().toByteArray())+".json"
}
data class PaletteSnapshot(val file:File,val sha:String):AutoCloseable { override fun close() { file.delete() } }
fun interface PaletteCapture { suspend fun capture(root:String,row:ItemWithTags):PaletteSnapshot }
data class PalettePixels(val width:Int,val height:Int,val argb:IntArray)
fun interface PaletteDecoder { suspend fun decode(snapshot:File,ext:String):PalettePixels? }
interface PaletteCache {
    fun read(identity:PaletteIdentity,sha:String):LocalPaletteRecord?
    fun write(identity:PaletteIdentity,record:LocalPaletteRecord)
}

/** Canonicalize only platform-trusted bases (Android /data/user/0 may alias /data/data).
 * Owned leaves are appended afterward and must pass NOFOLLOW checks without canonicalization. */
fun privatePaletteDirectory(dataDir:File?,cacheDir:File?,leaf:String):File {
    require(leaf.isNotEmpty() && leaf!="." && leaf!=".." && leaf.none { it=='/' || it=='\\' || it.isISOControl() })
    val data=requireNotNull(dataDir) { "app data directory unavailable" }.canonicalFile.toPath()
    val cache=requireNotNull(cacheDir) { "cache directory unavailable" }.canonicalFile.toPath()
    require(cache!=data && cache.startsWith(data)) { "cache directory outside app data" }
    return File(cache.toFile(),leaf)
}

/** Only this private cache namespace is supported; reject links before touching existing paths.
 * This guards preexisting corruption, not malicious same-UID concurrent path replacement. */
internal object PaletteFileBoundary {
    fun directory(file:File,create:Boolean):Boolean {
        val path=file.toPath().toAbsolutePath()
        require(path.none { it.toString()==".." || it.toString()=="." })
        var part=path.root ?: error("absolute path required")
        for(component in path) {
            part=part.resolve(component)
            if(!Files.exists(part,NOFOLLOW_LINKS)) {
                if(!create) return false
                Files.createDirectory(part)
            }
            require(!Files.isSymbolicLink(part) && Files.isDirectory(part,NOFOLLOW_LINKS)) { "unsafe palette directory" }
        }
        return true
    }
    fun regularOrMissing(file:File):Boolean {
        val path=file.toPath()
        require(!Files.isSymbolicLink(path)) { "palette entry is a symbolic link" }
        if(!Files.exists(path,NOFOLLOW_LINKS)) return false
        require(Files.isRegularFile(path,NOFOLLOW_LINKS)) { "palette entry is not a regular file" }
        return true
    }
    fun entries(directory:File):List<File> {
        // Preflight the full cold-cleanup list before deleting even one entry.
        val entries=directory.listFiles()?.toList() ?: error("palette directory unavailable")
        entries.forEach { regularOrMissing(it) }
        return entries
    }
}

/** Resolve app cacheDir lazily: an unavailable Context cache directory cannot crash app assembly. */
class DeferredPaletteCache(private val factory:()->PaletteCache):PaletteCache {
    private var cached:PaletteCache?=null
    @Synchronized private fun cache()=cached ?: factory().also { cached=it }
    override fun read(identity:PaletteIdentity,sha:String):LocalPaletteRecord? = try { cache().read(identity,sha) }
        catch(e:CancellationException) { throw e } catch(_:Exception) { null }
    override fun write(identity:PaletteIdentity,record:LocalPaletteRecord) {
        try { cache().write(identity,record) } catch(e:CancellationException) { throw e } catch(_:Exception) { }
    }
}

/** Shared stream capture keeps hashing and decoder input on the exact same private snapshot. */
suspend fun capturePaletteStream(directory:File,limit:Long=PALETTE_SOURCE_LIMIT,open:()->InputStream):PaletteSnapshot {
    require(limit>=0); check(PaletteFileBoundary.directory(directory,create=true))
    // Dedicated directory + caller serialization: remove only our abandoned process-death files.
    PaletteFileBoundary.entries(directory).filter { it.name.startsWith("snapshot-") && it.name.endsWith(".tmp") }.forEach {
        check(it.delete() || !it.exists()) { "cannot clear abandoned snapshot" }
    }
    val file=File.createTempFile("snapshot-",".tmp",directory)
    try {
        val digest=MessageDigest.getInstance("SHA-256"); var total=0L
        open().use { input -> file.outputStream().use { output ->
            val buffer=ByteArray(64*1024)
            while(true) {
                currentCoroutineContext().ensureActive()
                val n=input.read(buffer); if(n<0) break
                total+=n; require(total<=limit) { "source exceeds limit" }
                digest.update(buffer,0,n); output.write(buffer,0,n)
            }
        } }
        currentCoroutineContext().ensureActive()
        return PaletteSnapshot(file,digest.digest().joinToString("") { "%02x".format(it) })
    } catch(t:Throwable) { file.delete();throw t }
}

@Serializable data class LocalPaletteBlock(val color:List<Int>,val population:Int,val ratio:Double)
@Serializable data class LocalPaletteRecord(val root:String,val source:String,val algorithm:String,val sourceSha:String,
    val status:String,val width:Int,val height:Int,val eligible:Int,val blocks:List<LocalPaletteBlock>) {
    fun valid(identity:PaletteIdentity,sha:String):Boolean {
        if(root!=identity.root || source!=identity.source || algorithm!=identity.algorithm || sourceSha!=sha ||
            !Regex("[0-9a-f]{64}").matches(sourceSha) || width !in 1..128 || height !in 1..128 || eligible !in 0..width*height) return false
        if(eligible==0) return status=="noEligiblePixels" && blocks.isEmpty()
        if(status!="palette" || blocks.size !in 1..5 || blocks.map { it.color }.distinct().size!=blocks.size) return false
        if(blocks.any { it.color.size!=3 || it.color.any { c -> c !in 0..255 } || it.population !in 1..eligible ||
            !it.ratio.isFinite() || abs(it.ratio-100.0*it.population/eligible)>1e-9 }) return false
        if(blocks.sumOf { it.population.toLong() }!=eligible.toLong()) return false
        val sorted=blocks.sortedWith(compareByDescending<LocalPaletteBlock> { it.population }.thenBy { it.color[0] }.thenBy { it.color[1] }.thenBy { it.color[2] })
        return blocks==sorted
    }
    fun state()=if(eligible==0) PaletteState(unknown="noEligiblePixels") else PaletteState(blocks.map {
        PaletteBlock(PaletteRGB(it.color[0].toDouble(),it.color[1].toDouble(),it.color[2].toDouble()),it.ratio)
    })
}

/** Advisory cache: bounded strict JSON, stable filename, and atomic replacement. No library paths. */
class FilePaletteCache(private val directory:File,private val maxEntries:Int=10000,private val maxBytes:Long=32L*1024*1024,
    private val replace:(File,File)->Unit={from,to -> Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit }):PaletteCache {
    companion object { const val RECORD_LIMIT=16*1024 }
    private val codec=Json { encodeDefaults=true;ignoreUnknownKeys=false;isLenient=false;coerceInputValues=false }
    private data class Entry(val bytes:Long,val stamp:Long)
    private var index:MutableMap<String,Entry>?=null
    private var totalBytes=0L
    init { require(maxEntries>0 && maxBytes>0) }
    @Synchronized override fun read(identity:PaletteIdentity,sha:String):LocalPaletteRecord? {
        return try {
        if(!PaletteFileBoundary.directory(directory,create=false)) return null
        val file=File(directory,identity.filename)
        if(!PaletteFileBoundary.regularOrMissing(file) || file.length() !in 1..RECORD_LIMIT.toLong()) null else {
            val bytes=file.inputStream().use { input ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(2048)
                while(true) { val n=input.read(buffer);if(n<0) break;require(output.size()+n<=RECORD_LIMIT);output.write(buffer,0,n) }
                output.toByteArray()
            }
            require(bytes.size<=RECORD_LIMIT)
            val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val element=StrictTagJson.parse(text)
            codec.decodeFromJsonElement<LocalPaletteRecord>(element).takeIf { it.valid(identity,sha) }
        }
        } catch(_:Exception) { null }
    }
    @Synchronized override fun write(identity:PaletteIdentity,record:LocalPaletteRecord) {
        var temp:File?=null
        try {
            if(!record.valid(identity,record.sourceSha)) return
            val bytes=codec.encodeToString(record).toByteArray(Charsets.UTF_8)
            if(bytes.size>RECORD_LIMIT || bytes.size>maxBytes) return
            check(PaletteFileBoundary.directory(directory,create=true))
            val target=File(directory,identity.filename)
            PaletteFileBoundary.regularOrMissing(target)
            // One cold index avoids O(scope^2) directory stat calls for thousands of new entries.
            // Android may evict cache files; stale index overcounts, so eviction cannot exceed limits.
            val entries=index ?: mutableMapOf<String,Entry>().also { loaded ->
                PaletteFileBoundary.entries(directory).forEach { file ->
                    if(file.name.endsWith(".tmp")) check(file.delete() || !file.exists())
                    else loaded[file.name]=Entry(file.length(),file.lastModified())
                }
                totalBytes=loaded.values.sumOf { it.bytes };index=loaded
            }
            // A replacement needs both old target and a new temp entry until atomic move.
            // If there is no non-target victim (e.g. maxEntries=1), preserve old and skip.
            while(entries.size+1>maxEntries || totalBytes+bytes.size>maxBytes) {
                val victim=entries.filterKeys { it!=target.name }.minWithOrNull(compareBy<Map.Entry<String,Entry>> { it.value.stamp }.thenBy { it.key }) ?: return
                val file=File(directory,victim.key)
                PaletteFileBoundary.regularOrMissing(file)
                if(!file.delete() && file.exists()) return
                totalBytes-=victim.value.bytes;entries.remove(victim.key)
            }
            temp=File.createTempFile("record-",".tmp",directory)
            temp.outputStream().use { it.write(bytes);it.fd.sync() }
            replace(temp,target)
            totalBytes-=entries[target.name]?.bytes ?: 0L
            entries[target.name]=Entry(bytes.size.toLong(),System.currentTimeMillis());totalBytes+=bytes.size
        } catch(_:Exception) { /* Cache failure never breaks browsing. */ }
        finally { temp?.delete() }
    }
}

/** Serial resolver; only small palettes survive in memory, never image buffers or temporary files. */
class LocalPaletteResolver(private val capture:PaletteCapture,private val decoder:PaletteDecoder,private val cache:PaletteCache,
    private val dispatcher:CoroutineDispatcher=Dispatchers.IO,private val memoryLimit:Int=10000) {
    private data class Key(val identity:PaletteIdentity,val generation:Long,val modified:Long,val session:String,val itemId:String)
    private val memory=object:LinkedHashMap<Key,PaletteState>(16,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Key,PaletteState>?)=size>memoryLimit
    }
    private val mutex=Mutex()
    suspend fun resolve(ticket:ColorTicket,row:ItemWithTags,session:String=""):PaletteState {
        val eagle=RawPalette.parse(row.item.palettesJson)
        if(eagle.known) return eagle
        if(row.item.ext.lowercase() !in setOf("png","jpg","jpeg","heic","webp")) return PaletteState(unknown="unsupported-format")
        return withContext(dispatcher) { mutex.withLock {
            currentCoroutineContext().ensureActive()
            val identity=PaletteIdentity(ticket.root,row.item.imageUri)
            val key=Key(identity,ticket.generation,row.item.lastModified,session,row.item.id)
            // Retain no palette from a previous root/scan epoch.
            memory.keys.removeAll { it.identity.root!=ticket.root || it.generation!=ticket.generation || it.session!=session }
            memory[key]?.let { return@withLock it }
            try {
                capture.capture(ticket.root,row).use { snapshot ->
                    currentCoroutineContext().ensureActive()
                    val cached=try { cache.read(identity,snapshot.sha) } catch(e:CancellationException) { throw e } catch(_:Exception) { null }
                    val record=if(cached!=null && cached.valid(identity,snapshot.sha)) cached else {
                        val pixels=decoder.decode(snapshot.file,row.item.ext) ?: return@withLock PaletteState(unknown="decode-unavailable")
                        // Native codec calls may ignore cancellation; discard their return before quantizing/caching.
                        currentCoroutineContext().ensureActive()
                        val result=SwiftMmcq.summarizeStraightArgb(pixels.argb,pixels.width,pixels.height)
                        LocalPaletteRecord(identity.root,identity.source,identity.algorithm,snapshot.sha,result.status,pixels.width,pixels.height,
                            result.eligiblePixels,result.palettes.map { LocalPaletteBlock(it.color,it.population,it.ratio) })
                    }
                    check(record.valid(identity,snapshot.sha))
                    currentCoroutineContext().ensureActive()
                    try { cache.write(identity,record) } catch(e:CancellationException) { throw e } catch(_:Exception) { }
                    currentCoroutineContext().ensureActive()
                    record.state().also { memory[key]=it }
                }
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { PaletteState(unknown="source-unavailable") }
            catch(_:OutOfMemoryError) { PaletteState(unknown="resource-unavailable") }
        } }
    }
}

/** A filter request is distinct from the scan generation, including A->B->A color changes. */
data class PaletteRequest(val ticket:ColorTicket,val sequence:Long)
data class PaletteProgress(val request:PaletteRequest,val done:Int,val total:Int)
class PaletteRequestGate {
    private var sequence=0L
    private var current:PaletteRequest?=null
    @Synchronized fun begin(ticket:ColorTicket)=PaletteRequest(ticket,++sequence).also { current=it }
    @Synchronized fun invalidate() { sequence++;current=null }
    @Synchronized fun accepts(request:PaletteRequest)=current==request
}
