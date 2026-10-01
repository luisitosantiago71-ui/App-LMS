package com.mechrobotix.aprendels

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

/** Large, aspect-preserving reference with playback controls. */
class ReferenceViewerActivity:ComponentActivity() {
    private var video:VideoView?=null
    private val executor=Executors.newSingleThreadExecutor()
    private var closing=false
    private var active=false
    private var position=0
    private var start=0
    private var end=0
    private lateinit var root:LinearLayout
    private val clip=object:Runnable {
        override fun run() {
            val view=video ?: return
            if(closing || !active) return
            if(end>start && view.isPlaying && view.currentPosition>=end) { view.pause(); view.seekTo(start) }
            root.postDelayed(this,60)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val letter=intent.getStringExtra("letter") ?: ""
        val mirror=if(intent.getBooleanExtra("mirror",false)) -1f else 1f
        position=savedInstanceState?.getInt("position") ?: 0
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@ReferenceViewerActivity,R.color.background)) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v,insets ->
            val b=insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(b.left,b.top,b.right,b.bottom); insets
        }
        ViewCompat.requestApplyInsets(root)
        root.addView(TextView(this).apply { text="Referencia de $letter"; textSize=20f; setTextColor(androidx.core.content.ContextCompat.getColor(this@ReferenceViewerActivity,R.color.text_dark)); setPadding(16,8,16,8) })
        val area=FrameLayout(this); root.addView(area,LinearLayout.LayoutParams(-1,0,1f))
        val uri=if(intent.getBooleanExtra("video",false)) MotionReferenceStore.uri(this,letter) else null
        if(uri==null) {
            area.addView(ImageView(this).apply {
                scaleType=ImageView.ScaleType.FIT_CENTER; scaleX=mirror
                setImageResource(intent.getIntExtra("image",0))
                contentDescription="Referencia ampliada de $letter"
            },FrameLayout.LayoutParams(-1,-1))
        } else {
            val view=VideoView(this).apply { scaleX=mirror }
            video=view; area.addView(view,FrameLayout.LayoutParams(-1,-1,android.view.Gravity.CENTER))
            val controls=MediaController(this); controls.setAnchorView(area); view.setMediaController(controls)
            view.setOnErrorListener { _,_,_ -> Toast.makeText(this,"No se pudo reproducir el video",Toast.LENGTH_LONG).show(); true }
            view.setOnPreparedListener { player ->
                player.setVolume(0f,0f); view.seekTo(position.coerceAtLeast(start))
                if(active) view.start()
            }
            view.setOnCompletionListener { view.seekTo(start) }
            executor.execute {
                val frames=runCatching {
                    MotionReferenceStore.load(this,letter)?.frames
                }.getOrNull()
                runOnUiThread { if(!closing) {
                    start=frames?.firstOrNull()?.timeMs?.toInt() ?: 0
                    end=frames?.lastOrNull()?.timeMs?.toInt() ?: 0
                    view.setVideoURI(uri)
                } }
            }
        }
        root.addView(Button(this).apply { text="Cerrar"; setOnClickListener { finish() } })
        AppUi.styleButtons(root)
    }
    override fun onResume() { super.onResume(); active=true; video?.seekTo(position.coerceAtLeast(start)); video?.start(); root.post(clip) }
    override fun onPause() { active=false; position=video?.currentPosition ?: 0; video?.pause(); root.removeCallbacks(clip); super.onPause() }
    override fun onSaveInstanceState(outState:Bundle) { outState.putInt("position",video?.currentPosition ?: position); super.onSaveInstanceState(outState) }
    override fun onDestroy() { closing=true; root.removeCallbacks(clip); video?.stopPlayback(); executor.shutdown(); super.onDestroy() }
}
