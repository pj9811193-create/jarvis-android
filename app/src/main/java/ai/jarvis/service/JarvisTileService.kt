package ai.jarvis.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import ai.jarvis.memory.Memory

/**
 * ============================  QUICK SETTINGS TILE  ============================
 * A tile in the notification shade that starts/stops background listening.
 * =============================================================================
 */
@RequiresApi(24)
class JarvisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val memory = Memory(this)
        if (memory.backgroundEnabled()) {
            memory.setBackgroundEnabled(false)
            JarvisService.stop(this)
        } else {
            memory.setBackgroundEnabled(true)
            JarvisService.start(this)
        }
        render()
    }

    private fun render() {
        val tile: Tile = qsTile ?: return
        val on = Memory(this).backgroundEnabled()
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "JARVIS"
        tile.updateTile()
    }
}
