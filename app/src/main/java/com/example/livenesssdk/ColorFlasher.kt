import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.*

class ColorFlasher(private val view: View) {
    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
    private val handler = Handler(Looper.getMainLooper())
    private var index = 0

    private val runnable = object : Runnable {
        override fun run() {
            view.setBackgroundColor(colors[index])
            index = (index + 1) % colors.size
            handler.postDelayed(this, 500) // flash every 500ms
        }
    }

    fun start() {
        handler.post(runnable)
    }

    fun stop() {
        handler.removeCallbacks(runnable)
    }
}
