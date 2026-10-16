package dev.andrompv

import android.app.Application
import android.content.Context

class AndrompvApp : Application() {
    /** One player per process, shared by the activity, picture-in-picture and the media session. */
    val player: PlayerController by lazy { PlayerController(this) }
    val updater: AppUpdater by lazy { AppUpdater(this) }
}

val Context.player: PlayerController get() = (applicationContext as AndrompvApp).player
val Context.updater: AppUpdater get() = (applicationContext as AndrompvApp).updater
