package com.cztr.routinealarm.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView
import com.cztr.routinealarm.ui.components.R52Button
import com.cztr.routinealarm.ui.components.R52Card
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.design.R52Dimensions

/** Programmatic presentation with explicit state/callback injection; never XML-inflated. */
@SuppressLint("ViewConstructor")
class HomeScreenView(context: Context, state: HomeUiState, colors: R52Colors,
    onOpenPlan: () -> Unit, onOpenSystem: () -> Unit) : LinearLayout(context) {
    init {
        orientation=VERTICAL
        fun label(value:String,size:Float=16f,emphasis:Boolean=false)=TextView(context).apply {
            text=value; textSize=size;setTextColor(if(emphasis) colors.text else colors.secondaryText)
            if(emphasis) setTypeface(typeface,Typeface.BOLD)
            setPadding(0,dp(4),0,dp(8))
        }
        fun card()=R52Card(context,colors,dp(20)).apply {
            layoutParams=LayoutParams(-1,-2).apply { bottomMargin=dp(16) }
        }
        addView(label(state.greeting,24f,true))
        addView(card().apply {
            addView(label("WOCHENPLAN · VORSCHAU",12f))
            addView(label(state.title,28f,true))
            addView(label(state.detail))
            addView(label("Die Vorschau bestätigt keine Zustellung oder hörbare Ausgabe.",14f))
            val permission=state.planState==HomePlanState.PERMISSION_REQUIRED
            addView(R52Button(context,if(permission) "System prüfen" else "Plan ansehen",colors).apply {
                setOnClickListener { if(permission) onOpenSystem() else onOpenPlan() }
            })
        })
        addView(card().apply {
            addView(label("Nächste Schritte",21f,true))
            if(state.upcoming.isEmpty()) addView(label("Derzeit keine aktiven Termine in der Vorschau."))
            state.upcoming.forEach { item ->
                addView(LinearLayout(context).apply {
                    orientation=VERTICAL;tag="home_upcoming_item"
                    setPadding(0,dp(8),0,dp(8))
                    addView(label(item.timeLabel,14f))
                    addView(label(item.title,18f,true))
                    addView(label(item.categoryLabel,14f))
                })
            }
            addView(R52Button(context,"Alle anzeigen",colors).apply { setOnClickListener { onOpenPlan() } })
        })
        addView(card().apply {
            addView(label("Persönlicher Fortschritt noch nicht verfügbar",18f,true))
            addView(label("Der Wochenplan liefert keine Erledigt- oder Snooze-Zähler. Gespeicherte Testwecker-Ergebnisse stehen separat im Testverlauf.",14f))
        })
    }
    private fun dp(value:Int)=R52Dimensions.dp(context,value)
}
