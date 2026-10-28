package com.example.myapplication

import android.app.Application
import com.google.firebase.database.FirebaseDatabase

class MichiFeedApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)
    }
}
