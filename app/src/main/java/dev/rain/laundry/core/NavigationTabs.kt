package dev.rain.laundry.core

enum class NavigationTab(val title: String) {
    LAUNDRY("洗衣"), WATER("饮水"), ORDERS("订单"), SETTINGS("设置")
}

fun visibleNavigationTabs(laundryEnabled: Boolean = true, waterEnabled: Boolean = true): List<NavigationTab> = buildList {
    if (laundryEnabled) add(NavigationTab.LAUNDRY)
    if (waterEnabled) add(NavigationTab.WATER)
    add(NavigationTab.ORDERS)
    add(NavigationTab.SETTINGS)
}
