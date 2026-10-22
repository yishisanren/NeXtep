package io.github.lujinxin.nextep.workspace

/** Router activities can exist briefly before finishing or creating another task. */
internal class WorkspaceSearchReadiness {
    fun belongsToRequest(task: Int, existingBeforeLaunch: Set<Int>, observedInTarget: Set<Int>): Boolean =
        task !in existingBeforeLaunch || task in observedInTarget

    private var identity: String? = null
    private var since = 0L

    fun observe(task: Int?, component: String?, presentable: Boolean, now: Long,
        taobaoProducts: Boolean = false): Boolean {
        val router = component == "com.jingdong.app.mall.open.InterfaceActivity" ||
            component == "com.ss.android.ugc.aweme.app.DeepLinkHandlerActivity" ||
            component == "com.taobao.search.MainSearchRouteActivity" ||
            taobaoProducts && component == "com.taobao.browser.BrowserActivity"
        val current = if (task == null || component == null || !presentable || router) null else "$task:$component"
        if (current == null || identity != current) {
            identity = current
            since = now
            return false
        }
        return now - since >= 600L
    }
}
