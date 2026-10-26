package com.eagleviewer.app.data

import com.eagleviewer.app.data.db.*
import java.io.*
import java.nio.file.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalPaletteBoundaryTest {
    private val identity=PaletteIdentity("content://a/tree/root","content://a/source")
    private val sha="1".repeat(64)
    private fun record(id:PaletteIdentity=identity,hash:String=sha)=LocalPaletteRecord(id.root,id.source,id.algorithm,hash,"palette",1,1,1,listOf(LocalPaletteBlock(listOf(252,4,4),1,100.0)))
    private suspend fun <T> directory(block:suspend (File)->T):T {
        // macOS /var is a system symlink: use the real fixture parent to exercise our own links.
        val parent=File(System.getProperty("java.io.tmpdir")).toPath().toRealPath()
        val directory=Files.createTempDirectory(parent,"palette-boundary-").toFile()
        try { return block(directory) } finally { directory.deleteRecursively() }
    }
    private fun link(file:File,target:File) { Files.createSymbolicLink(file.toPath(),target.toPath()) }
    @Test fun `cache final and nested ancestor links refuse all reads writes cleanup and mkdir`()=runBlocking { directory { base ->
        val outside=File(base,"outside").also { it.mkdir() }
        val seed=FilePaletteCache(outside);seed.write(identity,record())
        val sentinel=File(outside,"source.tmp").also { it.writeText("keep") }
        val before=outside.listFiles()!!.associate { it.name to it.readBytes().toList() }
        val final=File(base,"final-link");link(final,outside)
        val ancestor=File(base,"ancestor-link");link(ancestor,outside)
        val nested=File(outside,"existing-child").also { it.mkdir() }
        val keep=File(nested,"source.tmp").also { it.writeText("nested-keep") }
        for(path in listOf(final,File(ancestor,"missing/child/cache"),File(ancestor,"existing-child"))) {
            val cache=FilePaletteCache(path)
            assertNull(cache.read(identity,sha));cache.write(identity,record())
        }
        assertEquals("keep",sentinel.readText());assertEquals("nested-keep",keep.readText())
        assertFalse(File(outside,"missing").exists())
        for((name,bytes) in before) assertEquals(bytes,File(outside,name).readBytes().toList())
        assertFalse(File(nested,identity.filename).exists())
    } }
    @Test fun `cache existing and dangling record links rejected and cold cleanup preflights all entries`()=runBlocking { directory { base ->
        val outside=File(base,"outside").also { it.mkdir() };val seed=FilePaletteCache(outside);seed.write(identity,record())
        val target=File(outside,identity.filename);val original=target.readBytes()
        val real=File(base,"cache").also { it.mkdir() };val entry=File(real,identity.filename);link(entry,target)
        val cache=FilePaletteCache(real);assertNull(cache.read(identity,sha));cache.write(identity,record(hash="2".repeat(64)))
        assertTrue(Files.isSymbolicLink(entry.toPath()));assertArrayEquals(original,target.readBytes())
        entry.delete();link(entry,File(outside,"absent.json"));assertNull(cache.read(identity,sha));cache.write(identity,record())
        assertTrue(Files.isSymbolicLink(entry.toPath()));assertFalse(File(outside,"absent.json").exists())
        entry.delete()
        val stale=File(real,"record-old.tmp").also { it.writeText("keep until preflight succeeds") }
        val tmp=File(real,"source.tmp");link(tmp,target)
        cache.write(identity,record());assertTrue(stale.exists());assertTrue(Files.isSymbolicLink(tmp.toPath()))
        assertArrayEquals(original,target.readBytes());assertFalse(entry.exists())
    } }
    @Test fun `scratch final ancestor and entry links reject before source open or cleanup`()=runBlocking { directory { base ->
        val outside=File(base,"outside").also { it.mkdir() }
        val sentinel=File(outside,"snapshot-original.tmp").also { it.writeText("keep") }
        val final=File(base,"final");link(final,outside)
        val ancestor=File(base,"ancestor");link(ancestor,outside)
        File(outside,"existing/child").mkdirs()
        var opened=0
        for(path in listOf(final,File(ancestor,"missing/child"),File(ancestor,"existing/child"))) {
            assertTrue(runCatching { capturePaletteStream(path) { opened++;ByteArrayInputStream(byteArrayOf(1)) } }.isFailure)
        }
        assertEquals(0,opened);assertEquals("keep",sentinel.readText());assertFalse(File(outside,"missing").exists())
        val real=File(base,"scratch").also { it.mkdir() }
        val own=File(real,"snapshot-abandoned.tmp").also { it.writeText("preserve until safe") }
        val entry=File(real,"snapshot-linked.tmp");link(entry,sentinel)
        assertTrue(runCatching { capturePaletteStream(real) { opened++;ByteArrayInputStream(byteArrayOf(1)) } }.isFailure)
        assertEquals(0,opened);assertTrue(own.exists());assertTrue(Files.isSymbolicLink(entry.toPath()));assertEquals("keep",sentinel.readText())
    } }
    @Test fun `advisory constructor provider and unsafe paths cannot fail browser assembly or a good derivation`()=runBlocking { directory { base ->
        var factories=0
        val deferred=DeferredPaletteCache { factories++;throw IOException("cacheDir unavailable") }
        assertEquals(0,factories);assertNull(deferred.read(identity,sha));deferred.write(identity,record());assertEquals(2,factories)
        val row=ItemWithTags(ItemEntity("i","i","png",1,1,1,0,0,0,0,"","",identity.source,null,null,""),emptyList())
        val ticket=ColorTicket(identity.root,1,true)
        val source=File(base,"source")
        val good=LocalPaletteResolver(PaletteCapture { _,_ -> capturePaletteStream(source) { ByteArrayInputStream(byteArrayOf(1)) } },
            PaletteDecoder { _,_ -> PalettePixels(1,1,intArrayOf(0xffff0000.toInt())) },deferred,Dispatchers.Unconfined)
        assertTrue(good.resolve(ticket,row).known)
        val outside=File(base,"outside").also { it.mkdir() };val unsafe=File(base,"link");link(unsafe,outside)
        val unsafeCache=DeferredPaletteCache { FilePaletteCache(unsafe) }
        val stillGood=LocalPaletteResolver(PaletteCapture { _,_ -> capturePaletteStream(source) { ByteArrayInputStream(byteArrayOf(1)) } },
            PaletteDecoder { _,_ -> PalettePixels(1,1,intArrayOf(0xffff0000.toInt())) },unsafeCache,Dispatchers.Unconfined)
        assertTrue(stillGood.resolve(ticket,row).known)
        val badSource=LocalPaletteResolver(PaletteCapture { _,_ -> capturePaletteStream(File(unsafe,"scratch")) { error("must not open") } },
            PaletteDecoder { _,_ -> error("must not decode") },unsafeCache,Dispatchers.Unconfined)
        assertFalse(badSource.resolve(ticket,row).known);assertTrue(outside.listFiles()!!.isEmpty())
    } }
    @Test fun `trusted platform app base alias works but owned cache and scratch symlinks stay rejected`()=runBlocking { directory { base ->
        val data=File(base,"real-app").also { it.mkdir() };val cache=File(data,"cache").also { it.mkdir() }
        val alias=File(base,"platform-alias");link(alias,data)
        val allowed=privatePaletteDirectory(alias,File(alias,"cache"),"local-palettes-v1")
        assertEquals(File(cache,"local-palettes-v1"),allowed)
        val normal=FilePaletteCache(allowed);normal.write(identity,record());assertEquals(record(),normal.read(identity,sha))
        val captured=privatePaletteDirectory(alias,File(alias,"cache"),"local-palette-snapshots")
        capturePaletteStream(captured) { ByteArrayInputStream(byteArrayOf(1)) }.close()
        assertTrue(captured.listFiles()!!.isEmpty())
        val insideAlias=File(data,"cache-alias");link(insideAlias,cache)
        assertEquals(File(cache,"via-cache-alias"),privatePaletteDirectory(data,insideAlias,"via-cache-alias"))
        val outside=File(base,"outside").also { it.mkdir() };val sentinel=File(outside,"snapshot-original.tmp").also { it.writeText("keep") }
        allowed.deleteRecursively();link(allowed,outside)
        assertNull(normal.read(identity,sha));normal.write(identity,record());assertFalse(File(outside,identity.filename).exists())
        captured.delete();link(captured,outside)
        assertTrue(runCatching { capturePaletteStream(privatePaletteDirectory(alias,File(alias,"cache"),"local-palette-snapshots")) { error("must not open") } }.isFailure)
        assertEquals("keep",sentinel.readText())
    } }
    @Test fun `trusted cache alias outside canonical app data and null directory providers are rejected`()=runBlocking { directory { base ->
        val data=File(base,"app").also { it.mkdir() };val sibling=File(base,"app-other").also { it.mkdir() }
        val outsideAlias=File(data,"cache");link(outsideAlias,sibling)
        assertTrue(runCatching { privatePaletteDirectory(data,outsideAlias,"local-palettes-v1") }.isFailure)
        assertTrue(runCatching { privatePaletteDirectory(data,sibling,"local-palettes-v1") }.isFailure)
        assertTrue(runCatching { privatePaletteDirectory(data,data,"local-palettes-v1") }.isFailure)
        assertTrue(runCatching { privatePaletteDirectory(null,outsideAlias,"local-palettes-v1") }.isFailure)
        assertTrue(runCatching { privatePaletteDirectory(data,null,"local-palettes-v1") }.isFailure)
        val deferred=DeferredPaletteCache { FilePaletteCache(privatePaletteDirectory(data,outsideAlias,"local-palettes-v1")) }
        assertNull(deferred.read(identity,sha));deferred.write(identity,record());assertTrue(sibling.listFiles()!!.isEmpty())
    } }
    @Test fun `replacement reserves temp entry byte budgets and max one keeps old without invoking replace`()=runBlocking { directory { base ->
        val one=File(base,"one");var oneMoves=0;var onePeak=0
        val single=FilePaletteCache(one,maxEntries=1,replace={from,to ->
            oneMoves++;onePeak=maxOf(onePeak,one.listFiles()!!.size)
            Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit
        })
        single.write(identity,record());single.write(identity,record(hash="2".repeat(64)))
        assertEquals(1,oneMoves);assertEquals(1,onePeak);assertEquals(record(),single.read(identity,sha))
        val many=File(base,"many");var peak=0;var bytePeak=0L;var fail=false
        val bounded=FilePaletteCache(many,maxEntries=3,maxBytes=3000,replace={from,to ->
            peak=maxOf(peak,many.listFiles()!!.size);bytePeak=maxOf(bytePeak,many.listFiles()!!.sumOf { it.length() })
            if(fail) throw IOException("replace denied")
            Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit
        })
        repeat(3) { bounded.write(identity.copy(source="source$it"),record(identity.copy(source="source$it"))) }
        val last=identity.copy(source="source2")
        bounded.write(last,record(last,hash="2".repeat(64)))
        assertTrue(peak<=3);assertTrue(bytePeak<=3000)
        fail=true;bounded.write(last,record(last,hash="3".repeat(64)))
        assertEquals(record(last,hash="2".repeat(64)),bounded.read(last,"2".repeat(64)))
        assertFalse(many.listFiles()!!.any { it.name.endsWith(".tmp") });assertTrue(peak<=3)
    } }
}
