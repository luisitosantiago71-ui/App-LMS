package com.mechrobotix.aprendels

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.mechrobotix.aprendels.databinding.ActivityImageSignsBinding
import java.util.concurrent.Executors

class ImageSignsActivity:ComponentActivity() {
    private lateinit var b:ActivityImageSignsBinding
    private val worker=Executors.newSingleThreadExecutor()
    private var closing=false;private var generation=0
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);b=ActivityImageSignsBinding.inflate(layoutInflater);setContentView(b.root);AppUi.insets(b.root);AppUi.styleButtons(b.root)
        AppUi.style(b.newImageSign);AppUi.style(b.backImageSigns,true)
        b.newImageSign.setOnClickListener { startActivity(Intent(this,ImageSignEditorActivity::class.java)) }
        b.backImageSigns.setOnClickListener { finish() }
    }
    override fun onResume() {
        super.onResume();val token=++generation;b.imageSignsStatus.text="Cargando señas…"
        worker.execute {
            val signs=mutableListOf<ImageSignStore.Sign>();var failures=0
            ImageSignStore.ids(this).forEach { id ->runCatching { ImageSignStore.load(this,id) }.onSuccess { signs.add(it) }.onFailure { failures++ } }
            runOnUiThread { if(!closing && token==generation) {
                b.imageSignsList.removeAllViews()
                b.imageSignsStatus.text=if(signs.isEmpty()) "Prepara tu primera imagen para practicar." else "${signs.size} señas listas"
                if(failures>0)b.imageSignsStatus.append(" · $failures archivos no se pudieron leer; se conservaron sus datos.")
                signs.sortedBy { it.name.lowercase() }.forEach { sign ->
                    val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;background=ContextCompat.getDrawable(context,R.drawable.bg_card);setPadding(16,16,16,16) }
                    card.addView(TextView(this).apply { text=sign.name;textSize=21f;setTextColor(ContextCompat.getColor(context,R.color.text_dark)) })
                    card.addView(AppUi.style(Button(this)).apply { text="Practicar";setOnClickListener { startActivity(Intent(this@ImageSignsActivity,ImageSignPracticeActivity::class.java).putExtra("sign_id",sign.id)) } })
                    card.addView(AppUi.style(Button(this),true).apply { text="Revisar imagen y mapeo";setOnClickListener { startActivity(Intent(this@ImageSignsActivity,ImageSignEditorActivity::class.java).putExtra("sign_id",sign.id)) } })
                    b.imageSignsList.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=AppUi.dp(this@ImageSignsActivity,12) })
                }
            } }
        }
    }
    override fun onDestroy() { closing=true;generation++;worker.shutdown();super.onDestroy() }
}
