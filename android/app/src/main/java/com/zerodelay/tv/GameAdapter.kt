package com.zerodelay.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class GameAdapter(
    private val items: List<Game>,
    private val onClick: (Game) -> Unit,
) : RecyclerView.Adapter<GameAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.itemTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = items[position]
        holder.title.text = g.title
        holder.itemView.setOnClickListener { onClick(g) }
    }

    override fun getItemCount(): Int = items.size
}
