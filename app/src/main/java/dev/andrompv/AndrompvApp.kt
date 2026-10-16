package dev.andrompv

import android.app.Application
import android.content.Context

class AndrompvApp : Application() {
    /** One player per process, shared by the activity, picture-in-picture and the media session. */
    val player: PlayerController by lazy { PlayerController(this) }
}

val Context.player: PlayerController get() = (applicationContext as AndrompvApp).player
