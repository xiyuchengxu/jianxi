# 饮水协议与验证范围

基于用户提供的胖乖生活 1.144.0 APK，独立实现联网饮水机流程。参考类为 GoodsTypeEnum、ScanCodeViewModel、AfterPayUseAct、AfterPayVm、DeviceStartupVm、DeviceStartupAct、StartStatusAct、WaterSkuAdapter 和 ApiService。分析文件保存在本地 .tools/water，不随源码发布。

## 识别和计费

- 饮水机 categoryCode 为 04；使用 goods/scan/v2、goods/normal/details 和 goods/normal/skus 识别设备及出水口。
- 当前适配 payment=after、isBluetooth=0 的联网设备。
- 设备 extAttr.flowmeter=1 对应按升计费；flowmeter=0 对应按秒计费。出水口 extAttr.level[0].price 是原客户端展示的起始单价；保留高于分的精度，不当作一次性固定价格。多档 level 明确显示阶梯计费。

## 开始、状态和结算

| 接口 | 关键参数/处理 |
|---|---|
| payChannelRoute/afterPay | imei、useTicket=0、shopId=orgId、goodsCategory=04；仅使用无需另行开通的直接余额渠道 |
| payChannelRoute/addUserAfterPayChannel | 用户确认后选择 method=2，作为后付费余额渠道 |
| goods/water/unlock | skuId、promotions；头部传 imei、categoryCode、lat、lng，并保留已有请求签名 |
| machine/msg/sync | msgId；指令确认只用于跟进状态，不代替设备运行确认 |
| goods/water/sync | skuId；workStatus=1 可用、2 当前账户使用、3 他人使用 |
| goods/endWater | goodsId；再次确认当前账户的会话，且设备支持 supportAppUserFinish 才调用 |
| order/afterPay/creating | orderNo=同步结果中的 identify；按原客户端的轮询契约取得结算账单 |
| order/detail | 校验返回账单编号、余额支付类型及实际 payPrice，复用已有订单状态和付款流程 |

promotions 使用原客户端未选优惠/积分时的 -6、-7、-8 排除项，不购买水卡、会员或附加商品。用户确认开始使用时才选择余额支付及发起启动请求。

同步 status=3/5 表示结束，status=6 表示异常。启动前保存意图，网络失败后保留 UNKNOWN 状态，只查询和恢复。旧的已完成记录不能证明新启动已结束；保留启动前的 identify，并核对是否观察到本次运行或新的使用标识。不会结束 workStatus=3 的他人会话。

会话按当前登录账号分别加密保存。页面可见且应用处于前台时每三秒查询进行中的会话，离开页面或进入后台后停止定时查询。结算账单不会再次发送设备同步或启动指令。

## 验证范围

本地 MockWebServer 合同测试及纯状态测试覆盖设备类别、费率精度、真实字段名、余额渠道、超时后不重发、重复点击、旧记录、他人占用、远程结束支持和实际账单校验。Android 模拟器测试使用完全拦截网络的本地响应，不向正式服务发起扣款或启动请求。

联网真机的二维码兼容性、服务器字段的实际变体、出水与扣款结果仍需要现场确认。蓝牙连接、先付费饮水和免密签约没有实现。
