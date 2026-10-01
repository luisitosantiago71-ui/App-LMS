package com.mechrobotix.aprendels

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import com.mechrobotix.aprendels.databinding.ActivityLearningModulesBinding

class LearningModulesActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val b=ActivityLearningModulesBinding.inflate(layoutInflater);setContentView(b.root);AppUi.insets(b.root)
        fun button(label:String,enabled:Boolean=true,action:()->Unit) {
            b.moduleList.addView(AppUi.style(Button(this)).apply{
                text=label;isEnabled=enabled;alpha=if(enabled)1f else .55f
                setOnClickListener{action()}
            },LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=AppUi.dp(this@LearningModulesActivity,14)})
        }
        button("Practicar abecedario"){startActivity(Intent(this,PracticeSignActivity::class.java))}
        try {
            WordCatalog.load(this).forEach{module ->
                val ready=module.lessons.isNotEmpty()
                button(if(ready)module.title else "${module.title} · Próximamente",ready){
                    startActivity(Intent(this,WordPracticeActivity::class.java).putExtra("module_id",module.id))
                }
            }
        }catch(e:Exception){b.moduleList.addView(TextView(this).apply{text="No se pudo cargar el catálogo: ${e.message}"})}
        button("Regresar"){finish()}
    }
}
