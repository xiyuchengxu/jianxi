package dev.rain.laundry.data

import dev.rain.laundry.diagnostics.DiagnosticOperation
import dev.rain.laundry.diagnostics.DiagnosticOperation.*

/** The diagnostics writer receives only fixed operation names. */
internal fun diagnosticOperation(path: String): DiagnosticOperation? = when (path) {
    "common/sms/sendCode" -> SMS
    "user/reg" -> LOGIN
    "goods/scan/v2", "goods/normal/details" -> DEVICE_LOOKUP
    "appoint/checkMachineCanUse" -> DEVICE_STATUS
    "goods/normal/skus" -> MODE_LIST
    "trade/preview/v4" -> QUOTE
    "trade/create" -> ORDER_CREATE
    "order/detail" -> ORDER_QUERY
    "order/sync" -> ORDER_SYNC
    "order/cancel" -> ORDER_CANCEL
    "payChannelRoute/prePay" -> PAYMENT_ROUTE
    "pay/unify/prePay" -> PAYMENT_PREPARE
    "pay/unify/pay" -> PAYMENT_SUBMIT
    "order/pay_status" -> PAYMENT_QUERY
    "goods/water/unlock" -> WATER_START
    "goods/water/sync" -> WATER_QUERY
    "goods/endWater" -> WATER_STOP
    "machine/msg/sync" -> WATER_COMMAND_QUERY
    "order/afterPay/creating" -> WATER_SETTLEMENT
    "payChannelRoute/afterPay" -> WATER_ROUTE
    "payChannelRoute/addUserAfterPayChannel" -> WATER_ROUTE_SELECT
    "payChannelRoute/againPay" -> WATER_BILL_ROUTE
    "popup/shop" -> WATER_AUTH_POLICY
    "alipay/isSign" -> AUTHORIZATION_QUERY
    "alipay/signUrl/pinpoint" -> AUTHORIZATION_OPEN
    else -> null
}
