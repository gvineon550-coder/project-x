package com.tvplayer.app
import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
class ChannelListAdapter(private val items: List<Channel>, private val onClick: (Channel) -> Unit) : RecyclerView.Adapter<ChannelListAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) { val name: TextView = v.findViewById(R.id.panelChannelName) }
    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH = VH(LayoutInflater.from(p.context).inflate(R.layout.item_panel_channel, p, false))
    override fun onBindViewHolder(h: VH, pos: Int) {
        val ch = items[pos]
        h.name.text = "${pos + 1}. ${ch.name}"
        h.itemView.setOnClickListener { onClick(ch) }
    }
    override fun getItemCount() = items.size
}
