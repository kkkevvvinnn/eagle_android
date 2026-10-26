/*
 * Kotlin translation of ColorThiefSwift MMCQ.swift,
 * pinned ac04a95acca965f8030a01293c31621b2a812c39.
 * Copyright (c) 2019 Kazuki Ohara <kazuki.ohara@gmail.com>
 * MIT License; retain the accompanying LICENSE and NOTICE files.
 * Source: https://github.com/yamoridon/ColorThiefSwift/blob/ac04a95acca965f8030a01293c31621b2a812c39/ColorThiefSwift/Classes/MMCQ.swift
 * Changes: pure JVM types; only fixed maxColors=5, quality=1, ignoreWhite=false;
 * excludes native color/nearest-color APIs; adds separately named RGBA adapter
 * and production PaletteExtraction.swift population reducer.
 * Upstream thanks: Lokesh Dhakar (original Color Thief), Sven Woltmann (Java).
 */
package com.eagleviewer.app.data

import kotlin.math.min
import kotlin.math.max

/** Caller supplies already bounded, premultiplied sRGB RGBA8; this does not decode images. */
object SwiftMmcq {
    data class Block(val color: List<Int>, val population: Int, val ratio: Double)
    data class Result(val eligiblePixels: Int, val palettes: List<Block>) {
        val status: String get() = if (eligiblePixels == 0) "noEligiblePixels" else "palette"
    }
    private fun colorIndex(r: Int, g: Int, b: Int) = (r shl 10) + (g shl 5) + b
    private class Box(val h: IntArray, val lo: IntArray, val hi: IntArray) {
        private var cachedCount: Int? = null
        private var cachedVolume: Int? = null
        private var cachedAverage: List<Int>? = null
        fun copy() = Box(h, lo.copyOf(), hi.copyOf())
        fun count(): Int = cachedCount ?: run {
            var count = 0
            for (r in lo[0]..hi[0]) for (g in lo[1]..hi[1]) for (b in lo[2]..hi[2])
                count += h[colorIndex(r, g, b)]
            count.also { cachedCount = it }
        }
        fun volume(): Int = cachedVolume ?: ((hi[0]-lo[0]+1)*(hi[1]-lo[1]+1)*(hi[2]-lo[2]+1)).also { cachedVolume = it }
        fun average(): List<Int> = cachedAverage ?: run {
            var n = 0; val sums = IntArray(3)
            for (r in lo[0]..hi[0]) for (g in lo[1]..hi[1]) for (b in lo[2]..hi[2]) {
                val amount = h[colorIndex(r, g, b)]; n += amount
                // All terms are integers (8 * half-bin center), same truncation as Swift.
                sums[0] += amount * (r*8+4); sums[1] += amount * (g*8+4); sums[2] += amount * (b*8+4)
            }
            List(3) { if (n > 0) sums[it]/n else min(8*(lo[it]+hi[it]+1)/2, 255) }.also { cachedAverage = it }
        }
        fun axis(): Int {
            val r = hi[0]-lo[0]; val g = hi[1]-lo[1]; val b = hi[2]-lo[2]
            val largest = max(r,max(g,b))
            return if (r==largest) 0 else if (g==largest) 1 else 2
        }
    }
    private fun split(box: Box): List<Box> {
        if (box.count()==0) return emptyList()
        if (box.count()==1) return listOf(box)
        val axis=box.axis(); val partial=IntArray(32){-1}; var total=0
        for (i in box.lo[axis]..box.hi[axis]) {
            var sum=0
            when(axis) {
                0 -> for (g in box.lo[1]..box.hi[1]) for (b in box.lo[2]..box.hi[2]) sum+=box.h[colorIndex(i,g,b)]
                1 -> for (r in box.lo[0]..box.hi[0]) for (b in box.lo[2]..box.hi[2]) sum+=box.h[colorIndex(r,i,b)]
                2 -> for (r in box.lo[0]..box.hi[0]) for (g in box.lo[1]..box.hi[1]) sum+=box.h[colorIndex(r,g,i)]
            }
            total+=sum; partial[i]=total
        }
        val ahead=IntArray(32){if(partial[it]!=-1) total-partial[it] else -1}
        for (i in box.lo[axis]..box.hi[axis]) if (partial[i]>total/2) {
            val a=box.copy(); val b=box.copy()
            val left=i-box.lo[axis]; val right=box.hi[axis]-i
            var cut=if(left<=right) min(box.hi[axis]-1,i+right/2) else max(box.lo[axis],(i-1-left/2.0).toInt())
            while(cut<0 || partial[cut]<=0) cut++
            var count2=ahead[cut]
            while(count2==0 && cut>0 && partial[cut-1]>0) {cut--;count2=ahead[cut]}
            a.hi[axis]=cut; b.lo[axis]=cut+1
            return listOf(a,b)
        }
        error("VBox cannot be cut")
    }
    private val byCount=Comparator<Box>{a,b -> a.count().compareTo(b.count())}
    private val byProduct=Comparator<Box>{a,b ->
        val ac=a.count(); val bc=b.count()
        if(ac==bc) a.volume().compareTo(b.volume()) else (ac.toLong()*a.volume()).compareTo(bc.toLong()*b.volume())
    }
    private fun iterate(queue: MutableList<Box>, comparator: Comparator<Box>, target: Int) {
        var iterations=0
        while(iterations<1000) {
            val box=queue.lastOrNull() ?: break
            if(box.count()==0) {queue.sortWith(comparator);iterations++;continue}
            queue.removeAt(queue.lastIndex)
            queue.addAll(split(box));queue.sortWith(comparator)
            if(queue.size>=target) return
            iterations++
            if(iterations>1000) return
        }
    }
    fun summarizePremultipliedRgba(rgba: ByteArray, width: Int, height: Int): Result =
        summarizeRgba(rgba,width,height,true)
    /** Bitmap.getPixels-style straight sRGB ARGB, with no alpha division. No Bitmap dependency. */
    fun summarizeStraightArgb(argb: IntArray, width: Int, height: Int): Result {
        require(width in 1..128 && height in 1..128 && argb.size==width*height)
        val rgba=ByteArray(argb.size*4)
        for(i in argb.indices) {
            rgba[4*i]=(argb[i] ushr 16).toByte();rgba[4*i+1]=(argb[i] ushr 8).toByte()
            rgba[4*i+2]=argb[i].toByte();rgba[4*i+3]=(argb[i] ushr 24).toByte()
        }
        return summarizeRgba(rgba,width,height,false)
    }
    private fun summarizeRgba(rgba: ByteArray, width: Int, height: Int, premultiplied: Boolean): Result {
        require(width in 1..128 && height in 1..128 && rgba.size==width*height*4)
        val histogram=IntArray(32768); val lo=IntArray(3){255}; val hi=IntArray(3)
        var eligible=0
        for(i in rgba.indices step 4) {
            val alpha=rgba[i+3].toInt() and 255
            if(alpha<125) continue
            eligible++
            val rgb=IntArray(3){
                val value=rgba[i+it].toInt() and 255
                (if(premultiplied) min(255,(value*255+alpha/2)/alpha) else value) shr 3
            }
            for(c in 0..2) {lo[c]=min(lo[c],rgb[c]);hi[c]=max(hi[c],rgb[c])}
            histogram[colorIndex(rgb[0],rgb[1],rgb[2])]++
        }
        if(eligible==0) return Result(0,emptyList())
        val queue=mutableListOf(Box(histogram,lo,hi))
        iterate(queue,byCount,4) // ceil(0.75*5)
        queue.sortWith(byProduct)
        iterate(queue,byProduct,5)
        queue.reverse()
        val merged=mutableMapOf<List<Int>,Int>()
        for(box in queue) if(box.count()>0) {
            val color=box.average(); merged[color]=(merged[color]?:0)+box.count()
        }
        check(merged.isNotEmpty() && merged.size<=5 && merged.values.sum()==eligible)
        val blocks=merged.map{(color,n)->Block(color,n,100.0*n.toDouble()/eligible.toDouble())}.sortedWith {a,b ->
            if(a.population!=b.population) b.population.compareTo(a.population) else {
                var order=0
                for(i in 0..2) if(a.color[i]!=b.color[i]) {order=a.color[i].compareTo(b.color[i]);break}
                order
            }
        }
        return Result(eligible,blocks)
    }
}
