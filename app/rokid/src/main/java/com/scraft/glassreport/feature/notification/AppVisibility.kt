package com.scraft.glassreport.feature.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Activity と常駐サービスの受信担当を切り替えるためのプロセス内フラグ。 */
object AppVisibility {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible

    var activityVisible: Boolean
        get() = _visible.value
        set(value) {
            _visible.value = value
        }
}
