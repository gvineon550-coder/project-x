package com.tvplayer.app
import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
class ChannelListAdapter(private val items: List<Channel>, private val onClick: (Channel) -> Unit) : RecyclerView.Adapter<ChannelListAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) { val name: TextView = v.findViewById(R.id.panelChannelName) }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_panel_channel, p, false))
    override fun onBindViewHolder(h: VH, pos: Int) {
        h.name.text = "${pos + 1}. ${items[pos].name}"
        h.itemView.setOnClickListener { onClick(items[pos]) }
    }
    override fun getItemCount() = items.size
}
