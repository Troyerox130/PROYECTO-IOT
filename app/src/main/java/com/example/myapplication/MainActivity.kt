package com.example.myapplication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var valorTiempo: TextView
    private lateinit var valorCantidad: TextView
    private lateinit var tituloEstado: TextView
    private lateinit var iconoEstado: ImageView
    private lateinit var txtTelemetria: TextView

    private lateinit var preferencias: SharedPreferences

    private val IP_ARDUINO = "http://192.168.4.1"
    private var gramosObjetivo: Int = 250
    private var pesoActualPlato: Int = 0
    private var distanciaSensorCm: Int = 0
    private var tiempoRestanteMs: Long = 0L
    private var duracionProgramadaMs: Long = DURACION_INICIAL_MS
    private var comidaDisponible: Boolean = true
    private var dispensandoEnCurso: Boolean = false
    private var estadoConexion: String = "conectando..."
    private var notificacionEnviada: Boolean = false

    private var temporizador: CountDownTimer? = null
    private val CHANNEL_ID = "canal_inventario_balacat"

    private val handlerPolling = Handler(Looper.getMainLooper())
    private val tareaEstado = object : Runnable {
        override fun run() {
            consultarEstadoArduino()
            handlerPolling.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        preferencias = getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE)
        val modoOscuro = preferencias.getBoolean(CLAVE_MODO_OSCURO, false)
        AppCompatDelegate.setDefaultNightMode(
            if (modoOscuro) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gramosObjetivo = preferencias.getInt(CLAVE_GRAMOS, 250)
        duracionProgramadaMs = preferencias
            .getLong(CLAVE_DURACION_PROGRAMADA, DURACION_INICIAL_MS)
            .coerceAtLeast(10_000L)

        val fechaFinGuardada = preferencias.getLong(CLAVE_FECHA_FIN, -1L)
        val ahora = System.currentTimeMillis()

        if (fechaFinGuardada == -1L) {
            tiempoRestanteMs = duracionProgramadaMs
            guardarFechaFin(ahora + tiempoRestanteMs)
        } else {
            val restante = fechaFinGuardada - ahora
            if (restante > 0L) {
                tiempoRestanteMs = restante
            } else {
                tiempoRestanteMs = duracionProgramadaMs
                guardarFechaFin(ahora + tiempoRestanteMs)
            }
        }

        valorTiempo = findViewById(R.id.valorTiempo)
        valorCantidad = findViewById(R.id.valorCantidad)
        tituloEstado = findViewById(R.id.tituloEstado)
        iconoEstado = findViewById(R.id.iconoEstado)
        txtTelemetria = findViewById(R.id.txtTelemetria)

        valorCantidad.text = "${gramosObjetivo}g"
        actualizarTextoTelemetria()

        solicitarPermisoNotificaciones()
        crearCanalNotificaciones()

        if (tiempoRestanteMs > 0L) {
            iniciarCuentaRegresiva(tiempoRestanteMs)
        } else {
            valorTiempo.text = "00:00:00"
        }

        valorTiempo.setOnClickListener {
            mostrarSelectorTiempo()
        }

        valorCantidad.setOnClickListener {
            mostrarDialogoGramos()
        }

        iconoEstado.setOnClickListener {
            enviarOrdenDispensar()
        }

        txtTelemetria.setOnClickListener {
            mostrarMenuAjustes()
        }

        agregarBotonAjustes(modoOscuro)
        aplicarColoresTema(modoOscuro)

        handlerPolling.post(tareaEstado)
    }

    private fun agregarBotonAjustes(oscuro: Boolean) {
        val contenedorRaiz = findViewById<FrameLayout>(android.R.id.content)
        val btnAjustes = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(if (oscuro) Color.WHITE else Color.DKGRAY)
            contentDescription = "Ajustes"
            setPadding(24, 24, 24, 24)
            setOnClickListener { mostrarMenuAjustes() }
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 32
            rightMargin = 24
        }

        contenedorRaiz.addView(btnAjustes, params)
    }

    private fun aplicarColoresTema(oscuro: Boolean) {
        val contenedorRaiz = findViewById<FrameLayout>(android.R.id.content)
        val raizXml = contenedorRaiz.getChildAt(0) ?: return

        val colorFondo = if (oscuro) Color.parseColor("#121212") else Color.WHITE
        val colorTexto = if (oscuro) Color.WHITE else Color.parseColor("#212121")

        raizXml.setBackgroundColor(colorFondo)
        cambiarColorTextos(raizXml, colorTexto)
    }

    private fun cambiarColorTextos(vista: View, colorTexto: Int) {
        if (vista is TextView) {
            vista.setTextColor(colorTexto)
        } else if (vista is ViewGroup) {
            for (i in 0 until vista.childCount) {
                cambiarColorTextos(vista.getChildAt(i), colorTexto)
            }
        }
    }

    private fun consultarEstadoArduino() {
        Thread {
            try {
                val url = URL("$IP_ARDUINO/estado")
                val conexion = url.openConnection() as HttpURLConnection
                conexion.requestMethod = "GET"
                conexion.connectTimeout = 2500
                conexion.readTimeout = 2500

                if (conexion.responseCode == 200) {
                    val respuesta = conexion.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(respuesta)

                    val actual = json.optInt("actual", 0)
                    val comida = json.optBoolean("comida", true)
                    val dispensando = json.optBoolean("dispensando", false)
                    val dist = json.optInt("dist", 0)

                    runOnUiThread {
                        pesoActualPlato = actual
                        dispensandoEnCurso = dispensando
                        distanciaSensorCm = dist
                        estadoConexion = "conectado"
                        actualizarUIInventario(comida)
                        actualizarTextoTelemetria()
                    }
                }
                conexion.disconnect()
            } catch (e: Exception) {
                runOnUiThread {
                    estadoConexion = "sin conexion"
                    actualizarTextoTelemetria()
                }
            }
        }.start()
    }

    private fun enviarOrdenDispensar() {
        Thread {
            try {
                val url = URL("$IP_ARDUINO/dispensar?g=$gramosObjetivo")
                val conexion = url.openConnection() as HttpURLConnection
                conexion.requestMethod = "GET"
                conexion.connectTimeout = 3000
                conexion.readTimeout = 3000

                val codigo = conexion.responseCode
                conexion.disconnect()

                runOnUiThread {
                    if (codigo == 200) {
                        Toast.makeText(this, "Dispensando ${gramosObjetivo}g...", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Error HTTP $codigo al dispensar", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error: conectate al Wi-Fi Balacat", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun mostrarMenuAjustes() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }

        val switchOscuro = Switch(this).apply {
            text = "Modo oscuro"
            isChecked = preferencias.getBoolean(CLAVE_MODO_OSCURO, false)
            setPadding(0, 16, 0, 16)
        }

        val switchSensor = Switch(this).apply {
            text = "Sensor ultrasonico automatico"
            isChecked = preferencias.getBoolean(CLAVE_SENSOR_ACTIVO, true)
            setPadding(0, 16, 0, 16)
        }

        val lblDistancia = TextView(this).apply {
            text = "Distancia de activacion del sensor (3 a 100 cm):"
            setPadding(0, 24, 0, 8)
        }

        val inputDistancia = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(preferencias.getInt(CLAVE_DISTANCIA_SENSOR, 15).toString())
        }

        layout.addView(switchOscuro)
        layout.addView(switchSensor)
        layout.addView(lblDistancia)
        layout.addView(inputDistancia)

        AlertDialog.Builder(this)
            .setTitle("Ajustes de Balacat")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val oscuroNuevo = switchOscuro.isChecked
                val sensorNuevo = switchSensor.isChecked
                val distNueva = (inputDistancia.text.toString().toIntOrNull() ?: 15).coerceIn(3, 100)

                val cambioTema = oscuroNuevo != preferencias.getBoolean(CLAVE_MODO_OSCURO, false)

                preferencias.edit()
                    .putBoolean(CLAVE_MODO_OSCURO, oscuroNuevo)
                    .putBoolean(CLAVE_SENSOR_ACTIVO, sensorNuevo)
                    .putInt(CLAVE_DISTANCIA_SENSOR, distNueva)
                    .apply()

                enviarConfigArduino(distNueva, sensorNuevo)

                if (cambioTema) {
                    AppCompatDelegate.setDefaultNightMode(
                        if (oscuroNuevo) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                    )
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enviarConfigArduino(distanciaCm: Int, sensorActivo: Boolean) {
        val estadoSensor = if (sensorActivo) 1 else 0
        Thread {
            try {
                val url = URL("$IP_ARDUINO/config?dist=$distanciaCm&sensor=$estadoSensor")
                val conexion = url.openConnection() as HttpURLConnection
                conexion.requestMethod = "GET"
                conexion.connectTimeout = 3000
                conexion.readTimeout = 3000

                val codigo = conexion.responseCode
                conexion.disconnect()

                runOnUiThread {
                    if (codigo == 200) {
                        Toast.makeText(this, "Ajustes aplicados al Arduino", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Error HTTP $codigo al guardar", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Guardado en app (sin conexion al Arduino)", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun actualizarTextoTelemetria() {
        val estadoTexto = when {
            estadoConexion == "sin conexion" -> "Sin conexion (Wi-Fi Balacat)"
            dispensandoEnCurso -> "Dispensando comida..."
            !comidaDisponible -> "Sin alimento en contenedor"
            pesoActualPlato >= gramosObjetivo -> "Plato lleno ($estadoConexion)"
            else -> "Listo ($estadoConexion)"
        }
        txtTelemetria.text = "Plato: ${pesoActualPlato}g / ${gramosObjetivo}g | Sensor: ${distanciaSensorCm}cm | $estadoTexto"
    }

    private fun mostrarSelectorTiempo() {
        val horasActuales = (tiempoRestanteMs / 3600000).toInt()
        val minutosActuales = ((tiempoRestanteMs % 3600000) / 60000).toInt()
        val segundosActuales = ((tiempoRestanteMs % 60000) / 1000).toInt()

        val contenedor = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 16)
        }

        fun crearColumna(titulo: String, max: Int, valorInicial: Int): Pair<LinearLayout, NumberPicker> {
            val columna = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(24, 0, 24, 0)
            }
            val etiqueta = TextView(this).apply {
                text = titulo
                gravity = Gravity.CENTER
                textSize = 12f
            }
            val picker = NumberPicker(this).apply {
                minValue = 0
                maxValue = max
                value = valorInicial.coerceIn(0, max)
                setFormatter { String.format(Locale.getDefault(), "%02d", it) }
            }
            columna.addView(etiqueta)
            columna.addView(picker)
            return Pair(columna, picker)
        }

        val (colHoras, pickerHoras) = crearColumna("Horas", 23, horasActuales)
        val (colMinutos, pickerMinutos) = crearColumna("Min", 59, minutosActuales)
        val (colSegundos, pickerSegundos) = crearColumna("Seg", 59, segundosActuales)

        contenedor.addView(colHoras)
        contenedor.addView(colMinutos)
        contenedor.addView(colSegundos)

        AlertDialog.Builder(this)
            .setTitle("Programar proxima racion")
            .setView(contenedor)
            .setPositiveButton("Guardar") { _, _ ->
                val h = pickerHoras.value
                val m = pickerMinutos.value
                val s = pickerSegundos.value

                val totalSegundos = ((h * 3600) + (m * 60) + s).toLong()
                val nuevoTiempoSeg = if (totalSegundos == 0L) 10L else totalSegundos

                duracionProgramadaMs = nuevoTiempoSeg * 1000L
                preferencias.edit()
                    .putLong(CLAVE_DURACION_PROGRAMADA, duracionProgramadaMs)
                    .apply()

                tiempoRestanteMs = duracionProgramadaMs
                guardarFechaFin(System.currentTimeMillis() + tiempoRestanteMs)
                iniciarCuentaRegresiva(tiempoRestanteMs)
                Toast.makeText(this, "Horario actualizado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun iniciarCuentaRegresiva(milisegundos: Long) {
        temporizador?.cancel()
        if (milisegundos <= 0L) {
            tiempoRestanteMs = 0L
            guardarFechaFin(0L)
            valorTiempo.text = "00:00:00"
            return
        }
        temporizador = object : CountDownTimer(milisegundos, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                tiempoRestanteMs = millisUntilFinished
                val h = millisUntilFinished / 3600000
                val m = (millisUntilFinished % 3600000) / 60000
                val s = (millisUntilFinished % 60000) / 1000
                valorTiempo.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
            }

            override fun onFinish() {
                tiempoRestanteMs = 0L
                valorTiempo.text = "00:00:00"
                enviarOrdenDispensar()
                reiniciarTemporizadorProgramado()
            }
        }.start()
    }

    private fun reiniciarTemporizadorProgramado() {
        val duracion = duracionProgramadaMs.takeIf { it > 0L }
            ?: DURACION_INICIAL_MS

        duracionProgramadaMs = duracion
        tiempoRestanteMs = duracion

        val nuevaFechaFin = System.currentTimeMillis() + duracion
        guardarFechaFin(nuevaFechaFin)
        iniciarCuentaRegresiva(duracion)
    }

    private fun mostrarDialogoGramos() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(gramosObjetivo.toString())

        AlertDialog.Builder(this)
            .setTitle("Configurar Cantidad (Max 1000g)")
            .setMessage("Define la porcion a dispensar (1g a 1000g):")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val texto = input.text.toString().trim()
                val nuevosGramos = texto.toIntOrNull()

                if (nuevosGramos == null || nuevosGramos < 1 || nuevosGramos > 1000) {
                    Toast.makeText(
                        this,
                        "Ingresa un valor valido entre 1g y 1000g",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                gramosObjetivo = nuevosGramos
                preferencias.edit().putInt(CLAVE_GRAMOS, nuevosGramos).apply()
                valorCantidad.text = "${gramosObjetivo}g"
                actualizarTextoTelemetria()
                Toast.makeText(this, "Cantidad guardada: ${nuevosGramos}g", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun actualizarUIInventario(hayComida: Boolean) {
        comidaDisponible = hayComida
        if (comidaDisponible) {
            tituloEstado.text = "Comida disponible"
            iconoEstado.setImageResource(android.R.drawable.checkbox_on_background)
            iconoEstado.setColorFilter(Color.parseColor("#6DB814"))
            notificacionEnviada = false
        } else {
            tituloEstado.text = "Poca comida"
            iconoEstado.setImageResource(android.R.drawable.ic_delete)
            iconoEstado.setColorFilter(Color.parseColor("#D32F2F"))
            if (!notificacionEnviada) {
                lanzarNotificacionInventarioBajo()
                notificacionEnviada = true
            }
        }
    }

    private fun lanzarNotificacionInventarioBajo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Alerta Balacat: nivel bajo")
            .setContentText("El sensor detecto poco alimento en el contenedor principal.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(101, builder.build())
    }

    private fun crearCanalNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CHANNEL_ID,
                "Inventario Balacat",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(canal)
        }
    }

    private fun solicitarPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1
                )
            }
        }
    }

    private fun guardarFechaFin(fechaFin: Long) {
        preferencias.edit().putLong(CLAVE_FECHA_FIN, fechaFin).apply()
    }

    override fun onDestroy() {
        handlerPolling.removeCallbacks(tareaEstado)
        temporizador?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PREFERENCIAS = "balacat_configuracion"
        private const val CLAVE_GRAMOS = "gramos_objetivo"
        private const val CLAVE_FECHA_FIN = "fecha_fin_temporizador"
        private const val CLAVE_DURACION_PROGRAMADA = "duracion_programada_ms"
        private const val CLAVE_MODO_OSCURO = "modo_oscuro"
        private const val CLAVE_SENSOR_ACTIVO = "sensor_activo"
        private const val CLAVE_DISTANCIA_SENSOR = "distancia_sensor"
        private const val DURACION_INICIAL_MS = 20342000L
    }
}