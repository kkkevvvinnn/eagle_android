package com.eagleviewer.app.data

import com.eagleviewer.app.data.db.*
import androidx.paging.PagingSource
import java.io.*
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalPaletteTest {
    private val sha=paletteSha(byteArrayOf(1,2,3))
    private val identity=PaletteIdentity("content://a/tree/root","content://a/tree/root/document/root%2Fimages%2Fi.info%2Fi.png")
    private val ticket=ColorTicket(identity.root,1,true)
    private val red=ColorCondition(common=CommonPaletteColor.red)
    private fun row(id:String="i",raw:String="",uri:String=identity.source,modified:Long=1)=ItemWithTags(
        ItemEntity(id,id,"png",1,1,3,0,0,0,modified,"","",uri,null,null,raw),listOf(ItemTagCrossRef(id,"a"),ItemTagCrossRef(id,"a")))
    private fun record(id:PaletteIdentity=identity,hash:String=sha)=LocalPaletteRecord(id.root,id.source,id.algorithm,hash,"palette",1,1,1,listOf(LocalPaletteBlock(listOf(252,4,4),1,100.0)))
    private class MemoryCache:PaletteCache {
        var record:LocalPaletteRecord?=null;var reads=0;var writes=0
        override fun read(identity:PaletteIdentity,sha:String):LocalPaletteRecord? { reads++;return record?.takeIf { it.valid(identity,sha) } }
        override fun write(identity:PaletteIdentity,record:LocalPaletteRecord) { writes++;this.record=record }
    }
    private suspend fun <T> directory(block:suspend (File)->T):T {
        val directory=Files.createTempDirectory(File(System.getProperty("java.io.tmpdir")).toPath().toRealPath(),"palette-test").toFile()
        try { return block(directory) } finally { directory.deleteRecursively() }
    }
    @Test fun `valid Eagle wins without touching source decoder cache`()=runBlocking {
        val resolver=LocalPaletteResolver(PaletteCapture { _,_ -> error("no capture") },PaletteDecoder { _,_ -> error("no decode") },object:PaletteCache {
            override fun read(identity:PaletteIdentity,sha:String):LocalPaletteRecord?=error("no cache")
            override fun write(identity:PaletteIdentity,record:LocalPaletteRecord)=error("no cache")
        },Dispatchers.Unconfined)
        val state=resolver.resolve(ticket,row(raw="""[{"color":[255,0,0],"ratio":15}]"""))
        assertTrue(ColorFilter.matches(state,red))
    }
    @Test fun `straight kernel small goldens alpha gate transparent never black`() {
        assertEquals(listOf(252,4,4),SwiftMmcq.summarizeStraightArgb(intArrayOf(0x7dff0000),1,1).palettes.single().color)
        val invisible=SwiftMmcq.summarizeStraightArgb(intArrayOf(0x7c000000,0x00000000),2,1)
        assertEquals("noEligiblePixels",invisible.status);assertTrue(invisible.palettes.isEmpty())
        assertTrue(runCatching { SwiftMmcq.summarizeStraightArgb(IntArray(129),129,1) }.isFailure)
    }
    @Test fun `capture exact bytes hash and cleanup on overflow or opening failure`()=runBlocking { directory { directory ->
        capturePaletteStream(directory) { ByteArrayInputStream(byteArrayOf(1,2,3)) }.use { snapshot ->
            assertEquals(sha,snapshot.sha);assertArrayEquals(byteArrayOf(1,2,3),snapshot.file.readBytes())
        }
        assertTrue(directory.listFiles()!!.isEmpty())
        File(directory,"snapshot-interrupted.tmp").writeBytes(byteArrayOf(9))
        capturePaletteStream(directory) { ByteArrayInputStream(byteArrayOf(1)) }.close()
        assertTrue(directory.listFiles()!!.isEmpty())
        assertTrue(runCatching { capturePaletteStream(directory,2) { ByteArrayInputStream(byteArrayOf(1,2,3)) } }.isFailure)
        assertTrue(runCatching { capturePaletteStream(directory) { throw IOException() } }.isFailure)
        assertTrue(directory.listFiles()!!.isEmpty())
    } }
    @Test fun `capture midstream cancellation removes private snapshot`()=runBlocking { directory { directory ->
        val worker=Job()
        val result=runCatching { withContext(worker) {
            capturePaletteStream(directory) { object:ByteArrayInputStream(ByteArray(100)) {
                override fun read(b:ByteArray,off:Int,len:Int):Int { val n=super.read(b,off,len);worker.cancel();return n }
            } }
        } }
        assertTrue(result.exceptionOrNull() is CancellationException);assertTrue(directory.listFiles()!!.isEmpty())
    } }
    @Test fun `strict disk validation identity SHA version status ratio population dimensions sorting duplicates`()=runBlocking { directory { directory ->
        val cache=FilePaletteCache(directory);val valid=record();cache.write(identity,valid)
        assertEquals(valid,cache.read(identity,sha))
        assertNull(cache.read(identity,"0".repeat(64)));assertNull(cache.read(identity.copy(root="content://other/tree/root"),sha))
        val bad=listOf(valid.copy(sourceSha="x"),valid.copy(algorithm="old"),valid.copy(root="other"),valid.copy(width=129),valid.copy(height=0),
            valid.copy(eligible=2),valid.copy(status="error"),valid.copy(blocks=listOf(valid.blocks[0].copy(ratio=99.0))),
            valid.copy(blocks=listOf(valid.blocks[0].copy(population=0))),valid.copy(blocks=valid.blocks+valid.blocks))
        for(record in bad) assertFalse(record.toString(),record.valid(identity,sha))
        val unordered=valid.copy(width=2,eligible=2,blocks=listOf(LocalPaletteBlock(listOf(252,4,4),1,50.0),LocalPaletteBlock(listOf(4,4,4),1,50.0)))
        assertFalse(unordered.valid(identity,sha));assertTrue(unordered.copy(blocks=unordered.blocks.reversed()).valid(identity,sha))
        val file=File(directory,identity.filename)
        for(text in listOf("{", "{\"root\":\"a\",\"root\":\"b\"}"," ".repeat(FilePaletteCache.RECORD_LIMIT+1),"[]", "{\"x\":true}")) {
            file.writeText(text);assertNull(cache.read(identity,sha))
        }
        file.writeBytes(byteArrayOf(0xc3.toByte(),0x28));assertNull(cache.read(identity,sha))
        val transparent=valid.copy(status="noEligiblePixels",eligible=0,blocks=emptyList());cache.write(identity,transparent)
        assertFalse(cache.read(identity,sha)!!.state().known)
        assertFalse(ColorFilter.matches(transparent.state(),ColorCondition(common=CommonPaletteColor.black)))
    } }
    @Test fun `atomic replace failure preserves prior cache and removes temp while quotas evict old entries`()=runBlocking { directory { directory ->
        val good=record();val normal=FilePaletteCache(directory);normal.write(identity,good)
        FilePaletteCache(directory,replace={_,_ -> throw IOException("atomic move denied") }).write(identity,good.copy(sourceSha="0".repeat(64)))
        assertEquals(good,normal.read(identity,sha));assertFalse(directory.listFiles()!!.any { it.name.endsWith(".tmp") })
        val bounded=FilePaletteCache(directory,maxEntries=2,maxBytes=1500)
        repeat(5) { n -> val id=identity.copy(source="source$n");bounded.write(id,record(id)) }
        assertTrue(directory.listFiles()!!.size<=2);assertTrue(directory.listFiles()!!.sumOf { it.length() }<=1500)
        assertNotNull(bounded.read(identity.copy(source="source4"),sha))
        val tiny=FilePaletteCache(directory,maxBytes=1);tiny.write(identity,record());assertTrue(directory.listFiles()!!.size<=2)
    } }
    @Test fun `scope exact authority tree item and safe filename excludes outside sources`() {
        fun allowed(doc:String="root/images/i.info/a #?中文.png",id:String="i",authority:String="a",tree:String="root")=
            PaletteSourceScope.accepts("a",authority,"root",tree,doc,id)
        assertTrue(allowed())
        for(doc in listOf("other/images/i.info/a.png","root/images/j.info/a.png","root/images/i.info/nested/a.png","root/images/i.info/..","root/images/i.info/","root/images/i.info/a\\b","root/images/i.info/a\n")) assertFalse(doc,allowed(doc))
        assertFalse(allowed(authority="b"));assertFalse(allowed(tree="other"));assertFalse(allowed(id="../i"))
    }
    @Test fun `same epoch 1203 unknown palettes reuse across color request rescan reads SHA and no source cannot reuse disk`()=runBlocking { directory { directory ->
        var captures=0;var decodes=0;var bytes=byteArrayOf(1,2,3);var missing=false
        val cache=FilePaletteCache(File(directory,"cache"))
        val resolver=LocalPaletteResolver(PaletteCapture { _,_ ->
            captures++;if(missing) throw FileNotFoundException()
            capturePaletteStream(File(directory,"snapshots")) { ByteArrayInputStream(bytes) }
        },PaletteDecoder { _,_ -> decodes++;PalettePixels(1,1,intArrayOf(0xffff0000.toInt())) },cache,Dispatchers.Unconfined)
        val rows=(0..1202).map { row("i$it",uri="source$it") }
        val first=ColorProjection.create(ticket,rows,red,palette={ resolver.resolve(ticket,it) })
        val second=ColorProjection.create(ticket,rows,red,palette={ resolver.resolve(ticket,it) })
        assertEquals(1203,captures);assertEquals(1203,decodes);assertEquals(first,second)
        val newer=ticket.copy(generation=2)
        assertTrue(resolver.resolve(newer,rows.first()).known);assertEquals(1204,captures);assertEquals(1203,decodes)
        bytes=byteArrayOf(9);assertTrue(resolver.resolve(ticket.copy(generation=3),rows.first()).known);assertEquals(1204,decodes)
        missing=true;assertFalse(resolver.resolve(ticket.copy(generation=4),rows.first()).known)
        assertTrue(File(directory,"snapshots").listFiles()!!.isEmpty())
    } }
    @Test fun `new ViewModel session same numeric generation revalidates source instead of stale memory`()=runBlocking { directory { directory ->
        var captures=0;var decodes=0;var bytes=byteArrayOf(1,2,3)
        val resolver=LocalPaletteResolver(PaletteCapture { _,_ -> captures++;capturePaletteStream(directory) { ByteArrayInputStream(bytes) } },
            PaletteDecoder { _,_ -> decodes++;PalettePixels(1,1,intArrayOf(0xffff0000.toInt())) },MemoryCache(),Dispatchers.Unconfined)
        resolver.resolve(ticket,row(),"session-one");resolver.resolve(ticket,row(),"session-one")
        assertEquals(1,captures)
        bytes=byteArrayOf(9)
        resolver.resolve(ticket,row(),"session-two")
        assertEquals(2,captures);assertEquals(2,decodes)
    } }
    @Test fun `broken missing cache decode IO OOM continue and are retryable no permanent negative`()=runBlocking { directory { directory ->
        var attempts=0;var fail=0;val cache=MemoryCache()
        val resolver=LocalPaletteResolver(PaletteCapture { _,_ -> capturePaletteStream(directory) { ByteArrayInputStream(byteArrayOf(1,2,3)) } },
            PaletteDecoder { _,_ -> attempts++;when(fail) { 0 -> null;1 -> throw IOException();2 -> throw OutOfMemoryError();else -> PalettePixels(1,1,intArrayOf(0xffff0000.toInt())) } },cache,Dispatchers.Unconfined)
        repeat(3) { fail=it;assertFalse(resolver.resolve(ticket,row()).known);assertEquals(0,cache.writes) }
        fail=3;assertTrue(resolver.resolve(ticket,row()).known);assertEquals(4,attempts);assertEquals(1,cache.writes)
        assertTrue(directory.listFiles()!!.isEmpty())
        // A corrupted injected record is independently checked by the resolver, not trusted.
        cache.record=record().copy(sourceSha="0".repeat(64))
        assertTrue(resolver.resolve(ticket.copy(generation=2),row()).known);assertEquals(5,attempts)
    } }
    @Test fun `cache exceptions remain advisory unsupported does not open and transparent memo remains unknown`()=runBlocking { directory { directory ->
        var captures=0;var decodes=0
        val broken=object:PaletteCache {
            override fun read(identity:PaletteIdentity,sha:String):LocalPaletteRecord?=throw IOException()
            override fun write(identity:PaletteIdentity,record:LocalPaletteRecord) { throw IOException() }
        }
        val resolver=LocalPaletteResolver(PaletteCapture { _,_ -> captures++;capturePaletteStream(directory) { ByteArrayInputStream(byteArrayOf(1,2,3)) } },
            PaletteDecoder { _,_ -> decodes++;PalettePixels(1,1,intArrayOf(if(decodes==1) 0 else 0xffff0000.toInt())) },broken,Dispatchers.Unconfined)
        val unsupported=row().let { it.copy(item=it.item.copy(ext="gif")) }
        assertFalse(resolver.resolve(ticket,unsupported).known);assertEquals(0,captures)
        val invisible=resolver.resolve(ticket,row())
        assertFalse(invisible.known);assertFalse(ColorFilter.matches(invisible,ColorCondition(common=CommonPaletteColor.black)))
        assertEquals(invisible,resolver.resolve(ticket,row()));assertEquals(1,captures)
        assertTrue(resolver.resolve(ticket.copy(generation=2),row()).known)
        assertEquals(2,captures);assertTrue(directory.listFiles()!!.isEmpty())
    } }
    @Test fun `WebP animation flag chunks truncated sizes and disguised RIFF are rejected before decoding`()=runBlocking { directory { directory ->
        fun webp(type:String,body:ByteArray):File {
            val out=ByteArrayOutputStream()
            fun text(s:String) { out.write(s.toByteArray(Charsets.US_ASCII)) }
            fun u32(n:Int) { repeat(4) { out.write(n ushr (8*it) and 255) } }
            text("RIFF");u32(4+8+body.size+(body.size and 1));text("WEBP");text(type);u32(body.size);out.write(body)
            if(body.size and 1 !=0) out.write(0)
            return File(directory,"fixture.webp").also { it.writeBytes(out.toByteArray()) }
        }
        assertTrue(PaletteWebp.isStatic(webp("VP8X",ByteArray(10))))
        assertFalse(PaletteWebp.isStatic(webp("VP8X",ByteArray(10).also { it[0]=2 })))
        assertFalse(PaletteWebp.isStatic(webp("ANIM",ByteArray(6))))
        assertFalse(PaletteWebp.isStatic(webp("ANMF",ByteArray(16))))
        val truncated=webp("VP8X",ByteArray(10));truncated.appendBytes(byteArrayOf(1));assertFalse(PaletteWebp.isStatic(truncated))
        val size=webp("VP8X",ByteArray(10));val bytes=size.readBytes();bytes[16]=127;size.writeBytes(bytes);assertFalse(PaletteWebp.isStatic(size))
    } }
    @Test fun `decode late cancellation cleans snapshot and does not cache or publish`()=runBlocking { directory { directory ->
        val cache=MemoryCache();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var published=false
        val resolver=LocalPaletteResolver(PaletteCapture { _,_ -> capturePaletteStream(directory) { ByteArrayInputStream(byteArrayOf(1,2,3)) } },
            PaletteDecoder { _,_ -> withContext(NonCancellable) { entered.complete(Unit);release.await() };PalettePixels(1,1,intArrayOf(0xffff0000.toInt())) },cache,Dispatchers.Unconfined)
        val job=launch(start=CoroutineStart.UNDISPATCHED) { ColorProjection.create(ticket,listOf(row()),red,palette={ resolver.resolve(ticket,it) });published=true }
        entered.await();job.cancel();release.complete(Unit);job.join()
        assertFalse(published);assertEquals(0,cache.writes);assertTrue(directory.listFiles()!!.isEmpty())
    } }
    @Test fun `whole projection counts order placeholders detail index and new request ABA progress gates`()=runBlocking {
        val requests=PaletteRequestGate();val old=requests.begin(ticket);requests.invalidate();val next=requests.begin(ticket)
        assertFalse(requests.accepts(old));assertTrue(requests.accepts(next))
        val gate=ColorPublicationGate();val a=gate.finish(gate.begin("a"),true);val request=requests.begin(a)
        gate.begin("b");val a2=gate.finish(gate.begin("a"),true)
        assertFalse(gate.accepts(request.ticket,"a"));assertNotEquals(a,a2)
        val rows=(0..1202).map { row("i$it") };var resolved=0;val progress=mutableListOf<Int>();var published=false
        val release=CompletableDeferred<Unit>()
        val job=async(start=CoroutineStart.UNDISPATCHED) {
            ColorProjection.create(ticket,rows,red,palette={
                resolved++;if(resolved==2) release.await()
                record().state()
            },progress={done,total -> assertEquals(1203,total);progress+=done }).also { published=true }
        }
        assertFalse(published);assertEquals(listOf(0,1),progress)
        release.complete(Unit);val projection=job.await()
        assertEquals(rows,projection.rows);assertEquals(listOf(TagCount("a",1203)),projection.counts)
        assertEquals(1203,progress.last())
        val page=ColorPagingSource(projection.rows).load(PagingSource.LoadParams.Refresh(1001,120,true)) as PagingSource.LoadResult.Page
        assertEquals("i1100",page.data[1100-page.itemsBefore].item.id);assertEquals(1001,page.itemsBefore);assertEquals(82,page.itemsAfter)
    }
}
