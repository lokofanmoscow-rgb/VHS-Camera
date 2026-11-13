package com.vhscamera.app

/**
 * The look of the recording, chosen in the on-screen menu.
 *
 * CLASSIC is the look matched to the reference clips. REAL_VHS is a 90s CCD camcorder
 * recording on VHS at SP speed, played back into a capture card: picture and sound.
 */
enum class Look(val menuLabel: String) {
    CLASSIC("CLASSIC"),
    REAL_VHS("REAL VHS");

    fun next(): Look = values()[(ordinal + 1) % values().size]

    companion object {
        fun fromName(name: String?): Look = values().firstOrNull { it.name == name } ?: CLASSIC
    }
}
