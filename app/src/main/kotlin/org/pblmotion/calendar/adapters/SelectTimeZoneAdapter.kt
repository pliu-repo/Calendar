package org.pblmotion.calendar.adapters

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.pblmotion.calendar.activities.SimpleActivity
import org.pblmotion.calendar.databinding.ItemSelectTimeZoneBinding
import org.pblmotion.calendar.models.MyTimeZone
import org.fossify.commons.extensions.getProperTextColor

class SelectTimeZoneAdapter(val activity: SimpleActivity, var timeZones: ArrayList<MyTimeZone>, val itemClick: (Any) -> Unit) :
    RecyclerView.Adapter<SelectTimeZoneAdapter.TimeZoneViewHolder>() {
    val textColor = activity.getProperTextColor()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimeZoneViewHolder {
        return TimeZoneViewHolder(
            binding = ItemSelectTimeZoneBinding.inflate(activity.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: TimeZoneViewHolder, position: Int) {
        val timeZone = timeZones[position]
        holder.bindView(timeZone)
    }

    override fun getItemCount() = timeZones.size

    fun updateTimeZones(newTimeZones: ArrayList<MyTimeZone>) {
        timeZones = newTimeZones.clone() as ArrayList<MyTimeZone>
        notifyDataSetChanged()
    }

    inner class TimeZoneViewHolder(val binding: ItemSelectTimeZoneBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bindView(timeZone: MyTimeZone) {
            binding.apply {
                itemTimeZoneTitle.text = timeZone.zoneName
                itemTimeZoneShift.text = timeZone.title

                itemTimeZoneTitle.setTextColor(textColor)
                itemTimeZoneShift.setTextColor(textColor)

                itemSelectTimeZoneHolder.setOnClickListener {
                    itemClick(timeZone)
                }
            }
        }
    }
}
