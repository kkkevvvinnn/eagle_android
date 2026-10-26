package com.eagleviewer.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.*

@Serializable
data class PaletteRGB(val r: Double, val g: Double, val b: Double) {
    init { require(listOf(r,g,b).all { it.isFinite() && it in 0.0..255.0 }) }
    val hsv: List<Double> get() {
        val r=r/255; val g=g/255; val b=b/255
        val hi=max(r,max(g,b)); val lo=min(r,min(g,b)); val d=hi-lo
        var h=if (d==0.0) 0.0 else if (hi==r) 60*((g-b)/d%6) else if (hi==g) 60*((b-r)/d+2) else 60*((r-g)/d+4)
        if(h<0) h+=360
        return listOf(h,if(hi==0.0) 0.0 else d/hi,hi)
    }
    val lab: List<Double> get() {
        fun linear(c:Double):Double { val v=c/255; return if(v<=0.04045) v/12.92 else ((v+0.055)/1.055).pow(2.4) }
        val r=linear(r); val g=linear(g); val b=linear(b)
        val x=(0.4124564*r+0.3575761*g+0.1804375*b)/0.95047
        val y=0.2126729*r+0.7151522*g+0.0721750*b
        val z=(0.0193339*r+0.1191920*g+0.9503041*b)/1.08883
        fun f(t:Double):Double { val d=6.0/29; return if(t>d*d*d) t.pow(1.0/3) else t/(3*d*d)+4.0/29 }
        return listOf(116*f(y)-16,500*(f(x)-f(y)),200*(f(y)-f(z)))
    }
    fun deltaE76(other:PaletteRGB):Double { val x=lab; val y=other.lab; return sqrt(x.indices.sumOf { (x[it]-y[it]).pow(2) }) }
    val argb: Int get() = (0xff shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    companion object { fun fromInt(rgb:Int)=PaletteRGB(((rgb ushr 16) and 255).toDouble(),((rgb ushr 8) and 255).toDouble(),(rgb and 255).toDouble()) }
}

@Serializable
enum class CommonPaletteColor(val label:String,val hue:Double?,val swatch:Int) {
    red("红",0.0,0xff0000), orange("橙",30.0,0xff8000), yellow("黄",60.0,0xffff00),
    green("绿",120.0,0x00ff00), cyan("青",180.0,0x00ffff), blue("蓝",240.0,0x0000ff),
    purple("紫",280.0,0xaa00ff), magenta("洋红",320.0,0xff00aa),
    black("黑",null,0), white("白",null,0xffffff), gray("灰",null,0x808080)
}

/** Version 1 means dominant blocks totaling >=15%; never legacy nearest/top150. */
@Serializable
data class ColorCondition(val semanticsVersion:Int=1, val common:CommonPaletteColor?=null,
    val custom:PaletteRGB?=null, val tolerance:Double=25.0) {
    init { require(semanticsVersion==1 && (common!=null) != (custom!=null) && tolerance.isFinite() && tolerance in 10.0..60.0) }
    val rgb:PaletteRGB get()=custom ?: PaletteRGB.fromInt(common!!.swatch)
}

internal class RatioSum {
    var value=0.0; private var correction=0.0
    fun add(n:Double) { val a=n-correction; val next=value+a; correction=(next-value)-a; value=next }
    fun atLeast(n:Double)=value>=n || n-value<=8*Math.ulp(n)
    fun atMost(n:Double)=value<=n || value-n<=8*Math.ulp(n)
}
data class PaletteBlock(val color:PaletteRGB,val ratio:Double)
data class PaletteState(val blocks:List<PaletteBlock> = emptyList(),val unknown:String?=null) {
    val known:Boolean get()=unknown==null
}

/** Only palette payload is bounded/validated; malformed palettes do not discard item metadata. */
object RawPalette {
    const val MAX_CHARS=65536
    fun parse(text:String):PaletteState {
        if(text.isEmpty()) return PaletteState(unknown="missing")
        if(text.length>MAX_CHARS || !boundedDepth(text)) return PaletteState(unknown="bounded-input")
        return try { parseElement(EagleItemMeta.json.parseToJsonElement(text)) } catch(_:Exception) { PaletteState(unknown="invalid-json") }
    }
    private fun boundedDepth(text:String):Boolean {
        var depth=0; var quoted=false; var escaped=false
        for(c in text) { if(quoted) { if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false }
            else when(c) { '"'->quoted=true; '[','{'->{ depth++; if(depth>16) return false }; ']','}'->depth-- } }
        return true
    }
    fun parseElement(raw:JsonElement?):PaletteState {
        fun unknown(s:String)=PaletteState(unknown=s)
        if(raw==null || raw==JsonNull) return unknown("missing")
        val entries=raw as? JsonArray ?: return unknown("not-array")
        if(entries.isEmpty()) return unknown("empty")
        if(entries.size>64) return unknown("too-many-entries")
        fun number(v:JsonElement?):Double? = (v as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        val blocks=mutableListOf<PaletteBlock>(); val sum=RatioSum()
        for(entry in entries) {
            val obj=entry as? JsonObject ?: return unknown("invalid-entry")
            val channels=obj["color"] as? JsonArray ?: return unknown("invalid-entry")
            if(channels.size!=3) return unknown("invalid-entry")
            val c=channels.map { number(it) ?: return unknown("invalid-entry") }
            if(c.any { it !in 0.0..255.0 || it.roundToInt().toDouble()!=it }) return unknown("invalid-entry")
            val ratio=number(obj["ratio"]) ?: return unknown("invalid-entry")
            if(ratio !in 0.0..100.0) return unknown("invalid-entry")
            sum.add(ratio); if(!sum.atMost(100.5)) return unknown("total-exceeds-100.5")
            blocks+=PaletteBlock(PaletteRGB(c[0],c[1],c[2]),ratio)
        }
        return if(sum.value>0) PaletteState(blocks.toList()) else unknown("zero-total")
    }
    /** Older local serializer omitted default zero ratios. Reread source rather than guess. */
    fun needsLegacyRatioRepair(text:String):Boolean {
        if(text.length>MAX_CHARS || !boundedDepth(text) ||
            text.split("\"color\"").size <= text.split("\"ratio\"").size) return false
        return runCatching {
            (EagleItemMeta.json.parseToJsonElement(text) as? JsonArray)?.any {
                val entry=it as? JsonObject
                entry!=null && "color" in entry && "ratio" !in entry
            } ?: false
        }.getOrDefault(false)
    }
    /** Keep explicit zero ratios; default serializer omission would turn valid blocks unknown. */
    fun encode(palettes:List<EagleItemMeta.Palette>):String = buildJsonArray {
        palettes.forEach { block -> add(buildJsonObject {
            put("color",JsonArray(block.color.map { JsonPrimitive(it) }))
            put("ratio",JsonPrimitive(block.ratio))
        }) }
    }.toString()
    fun display(state:PaletteState):List<EagleItemMeta.Palette> = if(!state.known) emptyList() else state.blocks.map {
        EagleItemMeta.Palette(listOf(it.color.r.toInt(),it.color.g.toInt(),it.color.b.toInt()),it.ratio)
    }
}
object ColorFilter {
    fun hueDistance(a:Double,b:Double):Double { val d=abs(a-b)%360; return min(d,360-d) }
    fun blockMatches(rgb:PaletteRGB,common:CommonPaletteColor?,custom:PaletteRGB?,tolerance:Double):Boolean {
        if(!tolerance.isFinite() || tolerance !in 10.0..60.0) return false
        if(custom!=null) return rgb.deltaE76(custom)<=tolerance
        val hsv=rgb.hsv; val h=hsv[0]; val s=hsv[1]; val v=hsv[2]
        return when(common) {
            CommonPaletteColor.black -> v<=0.20
            CommonPaletteColor.white -> v>0.20 && s<=0.20 && v>=0.90
            CommonPaletteColor.gray -> v>0.20 && s<=0.20 && v<0.90
            null -> false
            else -> v>0.20 && s>0.20 && hueDistance(h,common.hue!!)<=tolerance
        }
    }
    fun matches(state:PaletteState,condition:ColorCondition?):Boolean = if(condition==null) true else matches(state,condition.common,condition.custom,condition.tolerance)
    fun matches(state:PaletteState,common:CommonPaletteColor?,custom:PaletteRGB?,tolerance:Double):Boolean {
        if(!state.known) return false
        val sum=RatioSum()
        state.blocks.forEach { if(blockMatches(it.color,common,custom,tolerance)) sum.add(it.ratio) }
        return sum.atLeast(15.0)
    }
}
