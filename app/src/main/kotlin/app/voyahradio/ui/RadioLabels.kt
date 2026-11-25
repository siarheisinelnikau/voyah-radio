package app.voyahradio.ui

import app.voyahradio.R
import app.voyahradio.domain.RadioTab

object RadioLabels {
    fun tab(tab: RadioTab): Int {
        return when (tab) {
            RadioTab.BELARUS -> R.string.belarus
            RadioTab.RUSSIA -> R.string.russia
            RadioTab.INTERNATIONAL -> R.string.international
            RadioTab.KAZAKHSTAN -> R.string.kazakhstan
            RadioTab.FAVORITES -> R.string.favorites
        }
    }
}
