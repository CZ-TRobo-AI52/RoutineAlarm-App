package com.cztr.routinealarm.ui.design

import android.graphics.Color

/** Presentation colors only; a color never supplies execution authority. */
data class R52Colors(
    val background: Int, val secondaryBackground: Int, val surface: Int,
    val elevated: Int, val overlay: Int, val stroke: Int,
    val text: Int, val secondaryText: Int, val mutedText: Int,
    val brand: Int, val accent: Int, val success: Int, val warning: Int,
    val critical: Int, val unknown: Int, val quarantine: Int,
    val training: Int, val supplement: Int, val calendar: Int,
) {
    companion object {
        private fun c(hex: String) = Color.parseColor(hex)
        val Dark = R52Colors(c("#07111A"), c("#0A1722"), c("#0E1C28"),
            c("#13283A"), c("#183249"), c("#22394A"), c("#F4F7FB"),
            c("#AAB8C6"), c("#718292"), c("#2F80FF"), c("#45C4FF"),
            c("#28D17C"), c("#FFB547"), c("#FF5C6A"), c("#A97BFF"),
            c("#8A98A7"), c("#16D6A2"), c("#A25CFF"), c("#FF7A5C"))
        // Light semantic foregrounds are darker than their dark-theme equivalents.
        val Light = R52Colors(c("#F5F8FB"), c("#EDF2F7"), c("#FFFFFF"),
            c("#E7F0FC"), c("#E0EAF4"), c("#DCE5EC"), c("#101820"),
            c("#445466"), c("#647382"), c("#2F80FF"), c("#1558B0"),
            c("#08783F"), c("#855000"), c("#BB2638"), c("#7041A5"),
            c("#536375"), c("#006E58"), c("#7041A5"), c("#A53E24"))
    }
}
