package io.github.lujinxin.nextep.systemui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

internal object WorkbenchIntents {
    enum class Payment { WECHAT_SCAN, WECHAT_PAY, ALIPAY_SCAN, ALIPAY_PAY }
    enum class Search { DELIVERY, SHOPPING }
    data class Target(val name: String, val intent: Intent)

    fun payment(action: Payment): List<Intent> {
        val wechat = action == Payment.WECHAT_SCAN || action == Payment.WECHAT_PAY
        if (wechat) {
            val scan = action == Payment.WECHAT_SCAN
            return listOf(
                view(if (scan) "weixin://scanqrcode" else "weixin://dl/money", "com.tencent.mm"),
                Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"))
                    .putExtra("LauncherUI.Shortcut.LaunchType", if (scan) "launch_type_scaner" else "launch_type_offline_wallet")
                    .putExtra("LauncherUI.From.Scaner.Shortcut", scan)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        val appId = if (action == Payment.ALIPAY_SCAN) "10000007" else "20000056"
        return listOf(view("alipays://platformapi/startapp?appId=$appId", "com.eg.android.AlipayGphone"))
    }

    fun searches(mode: Search, rawQuery: String): List<Target> {
        val query = rawQuery.trim()
        require(query.isNotEmpty() && query.length <= 200 && query.none { it.isISOControl() })
        val encoded = Uri.encode(query)
        fun jd(instant: Boolean): Intent {
            val params = if (instant) JSONObject().put("category", "jump").put("des", "m")
                .put("url", "https://daojia.jd.com/#/IndexSearch?keyword=$encoded&real_keyword=$encoded")
            else JSONObject().put("category", "jump").put("des", "productList")
                .put("from", "search").put("keyWord", encoded)
            return view("openapp.jdmobile://virtual?params=${Uri.encode(params.toString())}", "com.jingdong.app.mall")
        }
        return if (mode == Search.DELIVERY) listOf(
            Target("京东秒送", jd(true)),
            Target("淘宝闪购", view("taobao://tb.ele.me/app/eleme/et-search/result?keyword=$encoded", "com.taobao.taobao")),
            Target("美团外卖", view("imeituan://www.meituan.com/takeout/poi/search?query=$encoded", "com.sankuai.meituan")),
        ) else listOf(
            Target("拼多多", view("pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key=$encoded", "com.xunmeng.pinduoduo")),
            Target("抖音商城", view("snssdk1128://search?keyword=$encoded&search_style=commodity_center&hideMiddlePage=1", "com.ss.android.ugc.aweme")),
            Target("京东", jd(false)),
            Target("淘宝", view("taobao://s.taobao.com/search?q=$encoded", "com.taobao.taobao")),
        )
    }

    private fun view(uri: String, target: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        .setPackage(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
