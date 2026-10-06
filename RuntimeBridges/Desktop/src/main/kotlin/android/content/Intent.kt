package android.content

import android.net.Uri

open class Intent {
    constructor()
    constructor(action: String)
    constructor(action: String, uri: Uri)

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
    }
}
