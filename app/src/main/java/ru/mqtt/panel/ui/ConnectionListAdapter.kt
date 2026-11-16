package ru.mqtt.panel.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.mqtt.panel.R
import ru.mqtt.panel.data.MqttConnection
import ru.mqtt.panel.mqtt.ConnectionState

class ConnectionListAdapter(
    private val onConnect: (MqttConnection) -> Unit,
    private val onEdit: (MqttConnection) -> Unit,
    private val onDelete: (MqttConnection) -> Unit,
    private val onEnabledChange: (MqttConnection, Boolean) -> Unit
) : ListAdapter<MqttConnection, ConnectionListAdapter.ViewHolder>(DiffCallback()) {

    /** connectionId -> количество панелей (для диагностики импорта) */
    private var panelCounts: Map<Long, Int> = emptyMap()

    /** connectionId -> графана жива (по probe); null — ещё проверяется */
    private var grafanaOk: Map<Long, Boolean> = emptyMap()

    /** Живое состояние сервисов — для светодиодов (обновляется broadcast'ом).
     *  MQTT параллельный: connId -> состояние */
    private var mqttStates: Map<Long, ConnectionState> = emptyMap()
    private var nmState: ConnectionState? = null
    private var nmAlive: Boolean = false

    // цвета светодиодов
    private val cGreen = 0xFF43A047.toInt()
    private val cRed = 0xFFE53935.toInt()
    private val cYellow = 0xFFF9A825.toInt()
    private val cGray = 0xFF9E9E9E.toInt()

    fun setPanelCounts(counts: Map<Long, Int>) {
        panelCounts = counts
        notifyItemRangeChanged(0, itemCount)
    }

    fun setGrafanaStatus(ok: Map<Long, Boolean>) {
        grafanaOk = ok
        notifyItemRangeChanged(0, itemCount)
    }

    /** Обновить светодиоды MQTT/Narodmon из состояния сервисов */
    fun setMqttStates(
        mqtt: Map<Long, ConnectionState>,
        narodmon: ConnectionState?,
        narodmonAlive: Boolean
    ) {
        mqttStates = mqtt
        nmState = narodmon
        nmAlive = narodmonAlive
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
        private val tvHost: TextView = view.findViewById(R.id.tv_host)
        private val tvPanels: TextView = view.findViewById(R.id.tv_panels)
        private val tvSsl: TextView = view.findViewById(R.id.tv_ssl)
        private val tvWs: TextView = view.findViewById(R.id.tv_ws)
        private val flagsRow: LinearLayout = view.findViewById(R.id.flags_row)
        private val swEnabled: com.google.android.material.materialswitch.MaterialSwitch =
            view.findViewById(R.id.sw_enabled)
        private val btnEdit: ImageButton = view.findViewById(R.id.btn_edit)
        private val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)

        private val ledBoxMqtt: LinearLayout = view.findViewById(R.id.led_box_mqtt)
        private val ledBoxGrafana: LinearLayout = view.findViewById(R.id.led_box_grafana)
        private val ledBoxNarodmon: LinearLayout = view.findViewById(R.id.led_box_narodmon)
        private val ledMqtt: View = view.findViewById(R.id.led_mqtt)
        private val ledGrafana: View = view.findViewById(R.id.led_grafana)
        private val ledNarodmon: View = view.findViewById(R.id.led_narodmon)
        private val tvLedMqtt: TextView = view.findViewById(R.id.tv_led_mqtt)
        private val tvLedGrafana: TextView = view.findViewById(R.id.tv_led_grafana)
        private val tvLedNarodmon: TextView = view.findViewById(R.id.tv_led_narodmon)

        private fun paint(v: View, color: Int) {
            val bg = v.background.mutate()
            if (bg is GradientDrawable) bg.setColor(color)
        }

        /** Светодиод + подпись одним цветом: цвет и есть расшифровка статуса */
        private fun status(led: View, label: TextView, color: Int, text: String) {
            paint(led, color)
            label.text = text
            label.setTextColor(color)
        }

        fun bind(conn: MqttConnection) {
            tvName.text = conn.name
            tvHost.text = if (conn.narodmon) {
                // аккаунт Народного Мониторинга: хост фиксированный, важен логин
                "narodmon.ru · ${conn.username.ifBlank { "без логина" }}"
            } else {
                "${conn.host}:${conn.port}"
            }
            val count = panelCounts[conn.id] ?: 0
            tvPanels.text = itemView.context.getString(R.string.widgets_count, count)
            tvSsl.text = if (conn.narodmon) "" else if (conn.ssl) "🔒SSL" else ""
            tvWs.text = if (conn.narodmon) "" else if (conn.websocket) "WS" else ""
            // пустую строку флагов убираем целиком — иначе у карточки
            // остаётся пустой отступ снизу
            flagsRow.visibility =
                if (tvSsl.text.isEmpty() && tvWs.text.isEmpty()) View.GONE else View.VISIBLE

            // Выключатель подключения: слушатель снимаем перед установкой,
            // иначе setChecked на рецикле дёрнет колбэк чужой карточки
            swEnabled.setOnCheckedChangeListener(null)
            swEnabled.isChecked = conn.enabled != false
            swEnabled.setOnCheckedChangeListener { _, checked ->
                onEnabledChange(conn, checked)
            }

            // --- статусы сервисов: светодиод + подпись-расшифровка ---
            val ctx = itemView.context
            fun label(name: String, state: String) =
                ctx.getString(R.string.led_status, name, state)

            if (conn.enabled == false) {
                // выключенный выключателем: серый статус, без активных сервисов
                ledBoxMqtt.visibility = if (conn.narodmon) View.GONE else View.VISIBLE
                ledBoxGrafana.visibility = View.GONE
                ledBoxNarodmon.visibility = if (conn.narodmon) View.VISIBLE else View.GONE
                val text = ctx.getString(R.string.state_disabled)
                if (conn.narodmon) {
                    status(ledNarodmon, tvLedNarodmon, cGray, label("Narodmon", text))
                } else {
                    status(ledMqtt, tvLedMqtt, cGray, label("MQTT", text))
                }
            } else if (conn.narodmon) {
                // у аккаунта Народмона MQTT/Grafana не применимы; статус серый,
                // пока сервис опроса ни разу не запускался (это не ошибка)
                ledBoxMqtt.visibility = View.GONE
                ledBoxGrafana.visibility = View.GONE
                ledBoxNarodmon.visibility = View.VISIBLE
                if (!nmAlive) {
                    status(ledNarodmon, tvLedNarodmon, cGray,
                        label("Narodmon", ctx.getString(R.string.state_not_started)))
                } else {
                    val (color, text) = when (nmState) {
                        ConnectionState.CONNECTED -> cGreen to ctx.getString(R.string.state_online)
                        ConnectionState.CONNECTING -> cYellow to ctx.getString(R.string.state_connecting)
                        else -> cRed to ctx.getString(R.string.state_error)
                    }
                    status(ledNarodmon, tvLedNarodmon, color, label("Narodmon", text))
                }
            } else {
                ledBoxNarodmon.visibility = View.GONE
                ledBoxMqtt.visibility = View.VISIBLE
                // подключения живут параллельно: нет в сервисе — выключено/не подключалось
                val mqttState = mqttStates[conn.id]
                if (mqttState != null) {
                    val (color, text) = when (mqttState) {
                        ConnectionState.CONNECTED -> cGreen to ctx.getString(R.string.state_connected)
                        ConnectionState.CONNECTING -> cYellow to ctx.getString(R.string.state_connecting)
                        else -> cRed to ctx.getString(R.string.state_error)
                    }
                    status(ledMqtt, tvLedMqtt, color, label("MQTT", text))
                } else {
                    status(ledMqtt, tvLedMqtt, cGray,
                        label("MQTT", ctx.getString(R.string.state_inactive)))
                }
                if (conn.grafanaEnabled()) {
                    ledBoxGrafana.visibility = View.VISIBLE
                    val (color, text) = when (grafanaOk[conn.id]) {
                        true -> cGreen to ctx.getString(R.string.state_connected)
                        false -> cRed to ctx.getString(R.string.state_unreachable)
                        null -> cYellow to ctx.getString(R.string.state_checking)
                    }
                    status(ledGrafana, tvLedGrafana, color, label("Grafana", text))
                } else {
                    ledBoxGrafana.visibility = View.GONE
                }
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
