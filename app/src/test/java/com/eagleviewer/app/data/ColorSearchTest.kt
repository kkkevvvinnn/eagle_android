package com.eagleviewer.app.data

import androidx.paging.*
import com.eagleviewer.app.data.db.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ColorSearchTest {
    private fun fixture(name:String)=EagleItemMeta.json.parseToJsonElement(javaClass.getResource("/color/$name")!!.readText())
    @Test fun `4266 Swift boolean golden and known unknown statuses are identical`() {
        val inputs=fixture("palettes.json").jsonArray
        val expected=fixture("golden.json").jsonObject.getValue("palettes").jsonArray
        var checked=0
        for((index,input) in inputs.withIndex()) {
            val obj=input.jsonObject; val id=obj.getValue("id").jsonPrimitive.content
            val state=RawPalette.parse(obj.getValue("raw").toString())
            val row=expected[index].jsonObject
            assertEquals(id,row.getValue("id").jsonPrimitive.content)
            assertEquals(id,row.getValue("state").jsonPrimitive.content,if(state.known) "known" else "unknown/${state.unknown}")
            for((label,value) in row.getValue("matches").jsonObject) {
                val parts=label.split("/")
                val actual=if(label=="off") ColorFilter.matches(state,null) else {
                    val tolerance=parts[2].toDoubleOrNull()?:Double.NaN
                    if(parts[0]=="common") ColorFilter.matches(state,CommonPaletteColor.valueOf(parts[1]),null,tolerance)
                    else ColorFilter.matches(state,null,if(parts[1]=="red") PaletteRGB(255.0,0.0,0.0) else PaletteRGB(128.0,128.0,128.0),tolerance)
                }
                assertEquals("$id $label",value.jsonPrimitive.boolean,actual); checked++
            }
        }
        assertEquals(4266,checked)
    }
    @Test fun `10 numeric golden vectors HSV Lab DeltaE and neutral hue boundaries`() {
        val rows=fixture("golden.json").jsonObject.getValue("numeric").jsonArray
        assertEquals(10,rows.size)
        for(row in rows) {
            val obj=row.jsonObject; val c=obj.getValue("rgb").jsonArray.map { it.jsonPrimitive.double }; val rgb=PaletteRGB(c[0],c[1],c[2])
            for((key,actual) in listOf("hsv" to rgb.hsv,"lab" to rgb.lab))
                obj.getValue(key).jsonArray.forEachIndexed { i,v -> assertEquals(key,v.jsonPrimitive.double,actual[i],1e-9) }
            assertEquals(obj.getValue("deltaE76ToRed").jsonPrimitive.double,rgb.deltaE76(PaletteRGB(255.0,0.0,0.0)),1e-9)
            obj.getValue("blockCommon25").jsonObject.forEach { (name,value) -> assertEquals(name,value.jsonPrimitive.boolean,ColorFilter.blockMatches(rgb,CommonPaletteColor.valueOf(name),null,25.0)) }
        }
        assertEquals(20.0,ColorFilter.hueDistance(350.0,10.0),0.0); assertEquals(0.0,ColorFilter.hueDistance(-1.0,719.0),0.0)
        assertTrue(runCatching { PaletteRGB(Double.NaN,0.0,0.0) }.isFailure)
    }
    @Test fun `malformed palette isolated from metadata and display swatches bounded`() {
        for(raw in listOf("null","{}","[1]","[{\"color\":[1,2],\"ratio\":40}]","[{\"color\":[0,0,0],\"ratio\":true}]")) {
            val meta=EagleItemMeta.parse("""{"id":"keep","name":"正常","tags":["a"],"star":4,"palettes":$raw}""")
            assertEquals("keep",meta.id); assertEquals(listOf("a"),meta.tags); assertEquals(4,meta.star); assertTrue(meta.palettes.isEmpty()); assertNull(meta.primaryColor)
        }
        val valid=EagleItemMeta.parse("""{"id":"ok","name":"x","palettes":[{"color":[1,2,3],"ratio":15,"extra":42}]}""")
        assertEquals(listOf(1,2,3),valid.palettes.single().color)
        val zero=EagleItemMeta.parse("""{"id":"ok","name":"x","palettes":[{"color":[0,0,0],"ratio":0},{"color":[255,0,0],"ratio":15}]}""")
        val oldCached="""[{"color":[0,0,0]},{"color":[255,0,0],"ratio":15}]"""
        assertTrue(RawPalette.needsLegacyRatioRepair(oldCached)); assertFalse(RawPalette.parse(oldCached).known)
        val persisted=RawPalette.encode(zero.palettes)
        assertFalse(RawPalette.needsLegacyRatioRepair(persisted))
        assertTrue(RawPalette.parse(persisted).known); assertEquals(2,RawPalette.parse(persisted).blocks.size)
        assertTrue(ColorFilter.matches(RawPalette.parse(persisted),ColorCondition(common=CommonPaletteColor.red)))
        assertFalse(RawPalette.parse("[".repeat(17)+"]".repeat(17)).known); assertFalse(RawPalette.parse(" ".repeat(RawPalette.MAX_CHARS+1)).known)
    }
    @Test fun `legacy migration explicit preference clear and serialized semantics`() {
        val old=FilterPersistence.decode("""{"similarColor":-65536,"tags":["a"],"minStar":3,"nameQuery":"x"}""")
        assertTrue(old.andMode); assertEquals(PaletteRGB(255.0,0.0,0.0),old.colorCondition!!.custom); assertEquals(25.0,old.colorCondition.tolerance,0.0)
        val encoded=FilterPersistence.encode(old)
        assertFalse(encoded.contains("similarColor")); assertTrue(encoded.contains("semanticsVersion")); assertEquals(old,FilterPersistence.decode(encoded))
        val explicit=FilterPersistence.decode("""{"similarColor":255,"colorCondition":{"semanticsVersion":1,"common":"red","tolerance":10}}""")
        assertEquals(CommonPaletteColor.red,explicit.colorCondition!!.common)
        assertNull(FilterPersistence.decode("""{"similarColor":255,"colorCondition":null}""").effectiveColor)
        assertEquals(old.copy(colorCondition=null),old.clearColor())
        for(raw in listOf("{\"semanticsVersion\":2,\"common\":\"red\"}","{\"common\":\"red\",\"custom\":{\"r\":0,\"g\":0,\"b\":0}}","{\"common\":\"red\",\"tolerance\":9}"))
            assertTrue(runCatching { FilterPersistence.decode("{\"colorCondition\":$raw}") }.isFailure)
    }
    private fun row(id:String,palette:String="""[{"color":[255,0,0],"ratio":15}]""")=ItemWithTags(ItemEntity(id,id,"jpg",1,1,0,0,0,0,0,"","","content://test/$id",null,null,palette),listOf(ItemTagCrossRef(id,"a"),ItemTagCrossRef(id,"a"),ItemTagCrossRef(id,"é")))
    @Test fun `projection retains over 999 order literal identities and exact same counts`()=runBlocking {
        val input=(0..1200).map { row("i$it") }+listOf(row("é"),row("é"),row("unknown",""))
        val projection=ColorProjection.create(ColorTicket("content://a",1,true),input,ColorCondition(common=CommonPaletteColor.red))
        assertEquals(input.dropLast(1).map { it.item.id },projection.rows.map { it.item.id }); assertEquals(1203,projection.rows.size)
        assertEquals(mapOf("a" to 1203,"é" to 1203),projection.counts.associate { it.tag to it.cnt })
        assertEquals(input,input.filter { ColorFilter.matches(RawPalette.parse(it.item.palettesJson),null) })
        val query=ItemRepository.buildQuery(Filter(tags=linkedSetOf("a","b"),colorCondition=ColorCondition(common=CommonPaletteColor.red)))
        assertEquals(2,query.argCount); assertFalse(query.sql.contains("CASE")); assertFalse(query.sql.contains("1203"))
    }
    @Test fun `cancelled preparation never reaches publication even mid palette loop`()=runBlocking {
        var published=false
        val failure=runCatching {
            withContext(Job()) {
                val worker=currentCoroutineContext()[Job]!!
                val interrupting=object:AbstractList<ItemWithTags>() {
                    override val size=100
                    override fun get(index:Int):ItemWithTags { if(index==10) worker.cancel(); return row("i$index") }
                }
                ColorProjection.create(ColorTicket("a",1,true),interrupting,ColorCondition(common=CommonPaletteColor.red))
                published=true
            }
        }
        assertTrue(failure.exceptionOrNull() is CancellationException); assertFalse(published)
    }
    @Test fun `root ABA generation rejects late completion and failed switch blocks`() {
        val gate=ColorPublicationGate(); val a=gate.begin("content://one/root"); val b=gate.begin("content://two/root")
        assertFalse(gate.accepts(gate.finish(a,true),"content://one/root")); val a2=gate.begin("content://one/root")
        assertNotEquals(a.generation,a2.generation); gate.finish(b,true); assertFalse(gate.accepts(a2,"content://one/root"))
        val ready=gate.finish(a2,true); assertTrue(gate.accepts(ready,"content://one/root")); assertFalse(gate.accepts(ready,"content://other/root"))
        val failed=gate.begin("content://two/root"); gate.finish(failed,false); assertFalse(gate.accepts(failed,"content://two/root"))
    }
    @Test fun `late asynchronous A projection cannot publish after A B A or full URI change`()=runBlocking {
        val gate=ColorPublicationGate()
        var live=gate.finish(gate.begin("content://authority-a/root"),true)
        var root=live.root
        val old=ColorProjection.empty(live)
        val release=CompletableDeferred<Unit>()
        val late=async(start=CoroutineStart.UNDISPATCHED) { release.await(); old.isCurrent(live,root) }
        live=gate.begin("content://authority-b/root"); root=live.root
        assertFalse(old.isCurrent(live,root))
        live=gate.finish(gate.begin("content://authority-a/root"),true); root=live.root
        release.complete(Unit)
        assertFalse(late.await())
        assertFalse(ColorProjection.empty(live).isCurrent(live,"content://different-authority/root"))
        assertTrue(ColorProjection.empty(live).isCurrent(live,root))
    }
    @Test fun `paging arbitrary refresh prepend append placeholders global index empty`()=runBlocking {
        val rows=(0..400).map { row("i$it") }; val source=ColorPagingSource(rows)
        suspend fun page(p:PagingSource.LoadParams<Int>)=source.load(p) as PagingSource.LoadResult.Page<Int,ItemWithTags>
        val refresh=page(PagingSource.LoadParams.Refresh(73,120,true)); val prepend=page(PagingSource.LoadParams.Prepend(refresh.prevKey!!,60,true)); val append=page(PagingSource.LoadParams.Append(refresh.nextKey!!,60,true))
        assertEquals(73,refresh.itemsBefore); assertEquals(208,refresh.itemsAfter)
        assertEquals((13..252).map { "i$it" },(prepend.data+refresh.data+append.data).map { it.item.id }); assertEquals("i100",refresh.data[100-refresh.itemsBefore].item.id)
        val all=refresh.data.toMutableList()
        var before=refresh.prevKey
        while(before!=null) { val next=page(PagingSource.LoadParams.Prepend(before,60,true)); all.addAll(0,next.data); before=next.prevKey }
        var after=refresh.nextKey
        while(after!=null) { val next=page(PagingSource.LoadParams.Append(after,60,true)); all.addAll(next.data); after=next.nextKey }
        assertEquals(rows.map { it.item.id },all.map { it.item.id })
        val end=page(PagingSource.LoadParams.Refresh(399,120,true)); assertEquals(399,end.itemsBefore); assertEquals(0,end.itemsAfter); assertNull(end.nextKey)
        val state=PagingState(listOf(refresh),100,PagingConfig(60,initialLoadSize=120,enablePlaceholders=true),73); assertEquals(40,source.getRefreshKey(state))
        val empty=ColorPagingSource(emptyList()).load(PagingSource.LoadParams.Refresh<Int>(null,120,true)) as PagingSource.LoadResult.Page
        assertEquals(0,empty.itemsBefore); assertEquals(0,empty.itemsAfter); assertNull(empty.prevKey); assertNull(empty.nextKey)
    }
}
