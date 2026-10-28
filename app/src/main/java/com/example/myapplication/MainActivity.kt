package com.example.myapplication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.text.InputType
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var valorTiempo: TextView
    private lateinit var valorCantidad: TextView
    private lateinit var tituloEstado: TextView
    private lateinit var iconoEstado: ImageView
    private lateinit var txtTelemetria: TextView

    private lateinit var dbRef: DatabaseReference
    private lateinit var preferencias: SharedPreferences

    private var gramosObjetivo: Int = 250
    private var pesoActualPlato: Int = 0
    private var tiempoRestanteMs: Long = 0L
    private var enBloqueoSeguridad: Boolean = false
    private var comidaDisponibleIR: Boolean = true
    private var notificacionEnviada: Boolean = false

    private var temporizador: CountDownTimer? = null
    private val CHANNEL_ID = "canal_inventario_balacat"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferencias = getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE)
        gramosObjetivo = preferencias.getInt(CLAVE_GRAMOS, 250)
        val fechaFinGuardada = preferencias.getLong(CLAVE_FECHA_FIN, -1L)
        if (fechaFinGuardada == -1L) {
            tiempoRestanteMs = DURACION_INICIAL_MS
            guardarFechaFin(System.currentTimeMillis() + tiempoRestanteMs)
        } else {
            tiempoRestanteMs = (fechaFinGuardada - System.currentTimeMillis()).coerceAtLeast(0L)
        }

        valorTiempo = findViewById(R.id.valorTiempo)
        valorCantidad = findViewById(R.id.valorCantidad)
        tituloEstado = findViewById(R.id.tituloEstado)
        iconoEstado = findViewById(R.id.iconoEstado)
        txtTelemetria = findViewById(R.id.txtTelemetria)

        solicitarPermisoNotificaciones()
        crearCanalNotificaciones()

        dbRef = FirebaseDatabase.getInstance().getReference("dispensador")
        inicializarEstructuraFirebase()
        escucharCambiosFirebase()

        if (tiempoRestanteMs > 0L) {
            iniciarCuentaRegresiva(tiempoRestanteMs)
        } else {
            valorTiempo.text = "00:00:00"
        }

        // Tocar el tiempo para configurar el horario de dispensación
        valorTiempo.setOnClickListener {
            mostrarSelectorTiempo()
        }

        // Tocar los gramos para configurar el peso objetivo de la celda de carga
        valorCantidad.setOnClickListener {
            mostrarDialogoGramos()
        }

        // Tocar el botón central para enviar señal manual de dispensación a la app de tu amigo
        iconoEstado.setOnClickListener {
            enviarOrdenDispensar()
        }
    }

    private fun inicializarEstructuraFirebase() {
        dbRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    val datosIniciales = mapOf(
                        "gramos_objetivo" to preferencias.getInt(CLAVE_GRAMOS, 250),
                        "peso_actual" to 0,
                        "tiempo_restante_seg" to 20342L,
                        "comida_disponible" to true,
                        "dispensar_ahora" to false,
                        "bloqueo_activo" to false
                    )
                    dbRef.setValue(datosIniciales)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun escucharCambiosFirebase() {
        dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                gramosObjetivo = (snapshot.child("gramos_objetivo").getValue(Int::class.java)
                    ?: preferencias.getInt(CLAVE_GRAMOS, 250)).takeIf { it > 0 }
                    ?: preferencias.getInt(CLAVE_GRAMOS, 250)
                preferencias.edit().putInt(CLAVE_GRAMOS, gramosObjetivo).apply()
                pesoActualPlato = snapshot.child("peso_actual").getValue(Int::class.java) ?: 0
                val comidaDispDB = snapshot.child("comida_disponible").getValue(Boolean::class.java) ?: true
                enBloqueoSeguridad = snapshot.child("bloqueo_activo").getValue(Boolean::class.java) ?: false
                val dispensandoDB = snapshot.child("dispensar_ahora").getValue(Boolean::class.java) ?: false

                valorCantidad.text = "${gramosObjetivo}g"
                actualizarUIInventario(comidaDispDB)

                val estadoTexto = when {
                    dispensandoDB -> "Dispensando comida..."
                    enBloqueoSeguridad -> "BLOQUEO ACTIVO (Ignorando gato)"
                    else -> "Listo para dispensar"
                }
                txtTelemetria.text = "Plato: ${pesoActualPlato}g / ${gramosObjetivo}g | $estadoTexto"
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@MainActivity, "Error de conexión con Firebase", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun mostrarSelectorTiempo() {
        val horasActuales = (tiempoRestanteMs / 3600000).toInt()
        val minutosActuales = ((tiempoRestanteMs % 3600000) / 60000).toInt()

        TimePickerDialog(this, { _, horas, minutos ->
            // Si seleccionas 00:00, programa 10 segundos para que puedan probar rápido en vivo
            val nuevoTiempoSeg = if (horas == 0 && minutos == 0) 10L else ((horas * 3600) + (minutos * 60)).toLong()
            tiempoRestanteMs = nuevoTiempoSeg * 1000L
            guardarFechaFin(System.currentTimeMillis() + tiempoRestanteMs)
            dbRef.child("tiempo_restante_seg").setValue(nuevoTiempoSeg)
            dbRef.child("bloqueo_activo").setValue(false)
            iniciarCuentaRegresiva(tiempoRestanteMs)
            Toast.makeText(this, "Horario actualizado", Toast.LENGTH_SHORT).show()
        }, horasActuales, minutosActuales, true).show()
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
                guardarFechaFin(0L)
                valorTiempo.text = "00:00:00"
                if (enBloqueoSeguridad) {
                    dbRef.child("bloqueo_activo").setValue(false)
                    Toast.makeText(this@MainActivity, "Tiempo de bloqueo finalizado", Toast.LENGTH_SHORT).show()
                } else {
                    enviarOrdenDispensar()
                }
            }
        }.start()
    }

    private fun mostrarDialogoGramos() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(gramosObjetivo.toString())

        AlertDialog.Builder(this)
            .setTitle("Configurar Cantidad (Gramos)")
            .setMessage("Define el peso límite para la celda de carga:")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val texto = input.text.toString()
                if (texto.isNotEmpty()) {
                    val nuevosGramos = texto.toInt()
                    if (nuevosGramos <= 0) {
                        Toast.makeText(this, "La cantidad debe ser mayor que 0", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    gramosObjetivo = nuevosGramos
                    preferencias.edit().putInt(CLAVE_GRAMOS, nuevosGramos).apply()
                    dbRef.child("gramos_objetivo").setValue(nuevosGramos)
                    Toast.makeText(this, "Cantidad guardada: ${nuevosGramos}g", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enviarOrdenDispensar() {
        if (enBloqueoSeguridad) {
            Toast.makeText(this, "Bloqueo de seguridad activo. Ignorando solicitud.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!comidaDisponibleIR) {
            Toast.makeText(this, "Sin comida disponible en el contenedor.", Toast.LENGTH_SHORT).show()
            return
        }

        dbRef.child("dispensar_ahora").setValue(true)
        Toast.makeText(this, "Señal de dispensación enviada", Toast.LENGTH_SHORT).show()
    }

    private fun actualizarUIInventario(hayComida: Boolean) {
        comidaDisponibleIR = hayComida
        if (comidaDisponibleIR) {
            tituloEstado.text = "Comida disponible"
            iconoEstado.setImageResource(android.R.drawable.checkbox_on_background)
            iconoEstado.setColorFilter(Color.parseColor("#6DB814"))
            notificacionEnviada = false
        } else {
            tituloEstado.text = "¡Poca comida!"
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
            .setContentTitle("Alerta MichiFeed: nivel bajo")
            .setContentText("El sensor infrarrojo detectó poco alimento en el contenedor principal.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(101, builder.build())
    }
    private fun crearCanalNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CHANNEL_ID,
                "Inventario MichiFeed",
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
        temporizador?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PREFERENCIAS = "balacat_configuracion"
        private const val CLAVE_GRAMOS = "gramos_objetivo"
        private const val CLAVE_FECHA_FIN = "fecha_fin_temporizador"
        private const val DURACION_INICIAL_MS = (5 * 3600 + 39 * 60 + 2) * 1000L
    }
}