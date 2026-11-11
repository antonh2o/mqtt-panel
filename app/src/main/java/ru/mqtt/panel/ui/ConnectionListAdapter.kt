package ru.mqtt.panel.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.mqtt.panel.R
import ru.mqtt.panel.data.MqttConnection

class ConnectionListAdapter(
    private val onConnect: (MqttConnection) -> Unit,
    private val onEdit: (MqttConnection) -> Unit,
    private val onDelete: (MqttConnection) -> Unit
) : ListAdapter<MqttConnection, ConnectionListAdapter.ViewHolder>(DiffCallback()) {

    /** connectionId -> количество панелей (для диагностики импорта) */
    private var panelCounts: Map<Long, Int> = emptyMap()

    /** connectionId -> статус Grafana («подключено (v10.2)» / текст ошибки) */
    private var grafanaStatus: Map<Long, String> = emptyMap()

    fun setPanelCounts(counts: Map<Long, Int>) {
        panelCounts = counts
        notifyItemRangeChanged(0, itemCount)
    }

    fun setGrafanaStatus(status: Map<Long, String>) {
        grafanaStatus = status
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_connection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tv_name)
        private val tvType: TextView = view.findViewById(R.id.tv_type)
        private val tvHost: TextView = view.findViewById(R.id.tv_host)
        private val tvPanels: TextView = view.findViewById(R.id.tv_panels)
        private val tvGhStatus: TextView = view.findViewById(R.id.tv_gh_status)
        private val tvSsl: TextView = view.findViewById(R.id.tv_ssl)
        private val tvWs: TextView = view.findViewById(R.id.tv_ws)
        private val btnEdit: ImageButton = view.findViewById(R.id.btn_edit)
        private val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)

        fun bind(conn: MqttConnection) {
            tvName.text = conn.name
            // заголовок типа: mqtt / mqtt+grafana / narodmon
            tvType.text = conn.typeLabel()
            tvHost.text = if (conn.narodmon) {
                // аккаунт Народного Мониторинга: хост фиксированный, важен логин
                "narodmon.ru · ${conn.username.ifBlank { "без логина" }}"
            } else {
                "${conn.host}:${conn.port}"
            }
            val count = panelCounts[conn.id] ?: 0
            tvPanels.text = itemView.context.getString(R.string.panels_count, count)
            tvSsl.text = if (conn.narodmon) "" else if (conn.ssl) "🔒SSL " else ""
            tvWs.text = if (conn.narodmon) "" else if (conn.websocket) "WS " else ""
            if (conn.grafanaEnabled()) {
                tvGhStatus.visibility = View.VISIBLE
                tvGhStatus.text = grafanaStatus[conn.id]
                    ?: itemView.context.getString(R.string.grafana_status_connected)
            } else {
                tvGhStatus.visibility = View.GONE
            }

            // Кнопки справа — отдельно от карточки
            btnEdit.setOnClickListener { onEdit(conn) }
            btnDelete.setOnClickListener { onDelete(conn) }

            // Тап по карточке — открыть дашборд
            itemView.setOnClickListener { onConnect(conn) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<MqttConnection>() {
        override fun areItemsTheSame(old: MqttConnection, new: MqttConnection) = old.id == new.id
        override fun areContentsTheSame(old: MqttConnection, new: MqttConnection) = old == new
    }
}
