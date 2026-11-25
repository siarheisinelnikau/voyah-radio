package app.voyahradio

import android.app.Application
import app.voyahradio.data.RadioCatalog
import app.voyahradio.data.RadioPreferences
import app.voyahradio.data.StreamProbe

class RadioApp : Application() {
    private val catalog by lazy { RadioCatalog(this) }
    private val preferences by lazy { RadioPreferences(this) }
    private val streamProbe by lazy { StreamProbe(this) }

    fun catalog() = catalog
    fun preferences() = preferences
    fun streamProbe() = streamProbe
}
