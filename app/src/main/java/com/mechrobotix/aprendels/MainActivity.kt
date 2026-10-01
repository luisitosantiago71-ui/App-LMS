package com.mechrobotix.aprendels

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.mechrobotix.aprendels.databinding.ActivityMainBinding

class MainActivity:ComponentActivity() {
    private lateinit var b:ActivityMainBinding
    private lateinit var sensors:Bmi160Panel
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(::sensors.isInitialized)sensors.permissionResult(granted)
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);b=ActivityMainBinding.inflate(layoutInflater);setContentView(b.root);AppUi.insets(b.root)
        sensors=Bmi160Panel(this) { if(Build.VERSION.SDK_INT>=31) permission.launch(Manifest.permission.BLUETOOTH_CONNECT) else sensors.permissionResult(true) }
        sensors.attachInline(b.sensorContainer)
        b.btnLearn.setOnClickListener { startActivity(Intent(this,LearningModulesActivity::class.java)) }
        AppUi.styleButtons(b.root)
    }
    override fun onResume() { super.onResume();sensors.resume() }
    override fun onPause() { sensors.pause();super.onPause() }
    override fun onDestroy() { sensors.destroy();super.onDestroy() }
}
