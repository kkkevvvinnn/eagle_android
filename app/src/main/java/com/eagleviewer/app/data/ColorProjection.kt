package com.eagleviewer.app.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.eagleviewer.app.data.db.ItemWithTags
import com.eagleviewer.app.data.db.TagCount
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Full URI + monotonic generation: A->B->A cannot admit A's first result. */
data class ColorTicket(val root:String, val generation:Long, val ready:Boolean)
class ColorPublicationGate {
    var current=ColorTicket("",0,true); private set
    @Synchronized fun begin(root:String):ColorTicket {
        current=ColorTicket(root,current.generation+1,false); return current
    }
    @Synchronized fun finish(ticket:ColorTicket,success:Boolean):ColorTicket {
        if(current==ticket) current=ticket.copy(ready=success)
        return current
    }
    @Synchronized fun accepts(ticket:ColorTicket,activeRoot:String):Boolean =
        current==ticket && ticket.ready && ticket.root==activeRoot
}

/** Both current counts and paging use this one immutable, ordered metadata snapshot. */
data class ColorProjection(val ticket:ColorTicket,val rows:List<ItemWithTags>,val counts:List<TagCount>) {
    /** Publication/replay guard also checks full live URI, not just a cold Flow subscription. */
    fun isCurrent(live:ColorTicket,root:String):Boolean = ticket.ready && ticket.root==root &&
        (if(live.generation==0L) ticket.generation==0L else ticket==live)
    companion object {
        fun empty(ticket:ColorTicket)=ColorProjection(ticket,emptyList(),emptyList())
        suspend fun create(ticket:ColorTicket,input:List<ItemWithTags>,condition:ColorCondition):ColorProjection {
            val rows=ArrayList<ItemWithTags>(); val counts=linkedMapOf<String,Int>()
            for(row in input) {
                currentCoroutineContext().ensureActive()
                if(ColorFilter.matches(RawPalette.parse(row.item.palettesJson),condition)) {
                    rows+=row
                    row.tagNames.distinct().forEach { counts[it]=(counts[it]?:0)+1 }
                }
            }
            return ColorProjection(ticket,rows.toList(),counts.map { TagCount(it.key,it.value) })
        }
    }
}

/** Keys are exact exclusive boundaries, never rounded page numbers. */
class ColorPagingSource(private val rows:List<ItemWithTags>):PagingSource<Int,ItemWithTags>() {
    override suspend fun load(params:LoadParams<Int>):LoadResult<Int,ItemWithTags> {
        val total=rows.size
        val boundary=(params.key?:0).coerceIn(0,total)
        val start=if(params is LoadParams.Prepend) (boundary-params.loadSize).coerceAtLeast(0) else boundary
        val end=if(params is LoadParams.Prepend) boundary else (start+params.loadSize).coerceAtMost(total)
        return LoadResult.Page(rows.subList(start,end),if(start==0) null else start,
            if(end==total) null else end,itemsBefore=start,itemsAfter=total-end)
    }
    override fun getRefreshKey(state:PagingState<Int,ItemWithTags>):Int? =
        state.anchorPosition?.let { (it-state.config.initialLoadSize/2).coerceAtLeast(0) }
}
