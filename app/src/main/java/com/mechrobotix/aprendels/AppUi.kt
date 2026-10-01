package com.mechrobotix.aprendels

import android.content.Context
import android.content.res.ColorStateList
import android.widget.Button
import android.view.ViewGroup
import android.widget.LinearLayout
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

object AppUi {
    fun style(button:Button,secondary:Boolean=false):Button=button.apply {
        isAllCaps=false
        backgroundTintList=null
        background=ContextCompat.getDrawable(context,if(secondary) R.drawable.bg_button_secondary else R.drawable.bg_button_primary)
        setTextColor(ContextCompat.getColor(context,if(secondary) R.color.brand else R.color.white))
        textSize=14f
        minHeight=dp(context,48)
        setPadding(dp(context,14),dp(context,8),dp(context,14),dp(context,8))
    }
    fun styleButtons(root:View) {
        fun visit(view:View) {
            if(view is Button) {
                val idName=runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
                val label=view.text?.toString()?.lowercase().orEmpty()
                val primary=idName in setOf("btnLearn","btnAlphabet","btnNextSign","btnPracticePreview") ||
                    label.startsWith("practicar") || label.startsWith("guardar") || label.startsWith("siguiente") ||
                    label.startsWith("finalizar") || label.startsWith("mapear") || label.startsWith("buscar") ||
                    label.startsWith("conectar")
                style(view,secondary=!primary)
                val params=view.layoutParams as? ViewGroup.MarginLayoutParams
                if(params!=null) {
                    val gap=dp(view.context,8)
                    val parent=view.parent as? LinearLayout
                    if(parent?.orientation==LinearLayout.HORIZONTAL) {
                        if(parent.indexOfChild(view)>0) params.marginStart=maxOf(params.marginStart,gap)
                    } else if(params.topMargin==0) params.topMargin=gap
                    view.layoutParams=params
                }
            }
            if(view is ViewGroup) for(i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
    }
    fun insets(view:View) {
        val l=view.paddingLeft;val t=view.paddingTop;val r=view.paddingRight;val b=view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v,i ->
            val bars=i.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(l+bars.left,t+bars.top,r+bars.right,b+bars.bottom);i
        }; ViewCompat.requestApplyInsets(view)
    }
    fun checkTint(context:Context)=ColorStateList.valueOf(ContextCompat.getColor(context,R.color.brand))
    fun dp(context:Context,value:Int)=(value*context.resources.displayMetrics.density).toInt()
}
