package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val database = FirebaseDatabase.getInstance(
            "https://prueba-2-e60c5-default-rtdb.firebaseio.com/"
        )
        val usuariosRef = database.getReference("usuarios")

        // Crea un usuario por defecto en Firebase si aun no existe
        usuariosRef.child("admin").get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                usuariosRef.child("admin").setValue("1234")
            }
        }

        val inputUsuario = findViewById<EditText>(R.id.inputUsuario)
        val inputPassword = findViewById<EditText>(R.id.inputPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)

        btnLogin.setOnClickListener {
            val usuario = inputUsuario.text.toString().trim()
            val password = inputPassword.text.toString().trim()

            if (usuario.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnLogin.isEnabled = false
            usuariosRef.child(usuario).get()
                .addOnSuccessListener { snapshot ->
                    btnLogin.isEnabled = true
                    val passGuardada = snapshot.getValue(String::class.java)

                    // Permite entrar con el usuario de Firebase o el respaldo local admin / 1234
                    if (passGuardada == password || (usuario == "admin" && password == "1234")) {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    } else {
                        Toast.makeText(this, "Credenciales incorrectas", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    btnLogin.isEnabled = true
                    if (usuario == "admin" && password == "1234") {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    } else {
                        Toast.makeText(this, "Error al verificar en Firebase", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }
}