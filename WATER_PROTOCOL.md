# 饮水协议与验证范围

基于用户提供的胖乖生活 1.144.0 APK，独立实现联网饮水机流程。参考类为 GoodsTypeEnum、ScanCodeViewModel、AfterPayUseAct、AfterPayVm、DeviceStartupVm、DeviceStartupAct、StartStatusAct、WaterSkuAdapter 和 ApiService。分析文件保存在本地 .tools/water，不随源码发布。

## 识别和计费

- 饮水机 categoryCode 为 04；使用 goods/scan/v2、goods/normal/details 和 goods/normal/skus 识别设备及出水口。
- 当前适配 payment=after、isBluetooth=0 的联网设备，支持平台提供的直接余额与支付宝后付费渠道。
- 设备 extAttr.flowmeter=1 对应按升计费；flowmeter=0 对应按秒计费。出水口 extAttr.level[0].price 是原客户端展示的起始单价；保留高于分的精度，不当作一次性固定价格。多档 level 明确显示阶梯计费。

## 开始、状态和结算

| 接口 | 关键参数/处理 |
|---|---|
| payChannelRoute/afterPay | imei、useTicket=0、shopId=orgId、goodsCategory=04；解析直接余额 2 与支付宝 13/15/103，保留选择并排除会员、不可选与第三方卡渠道 |
| popup/shop | 渠道 15 按原版使用 shopId、goodsId、tradeMain、multipleSwitch=2 核对设备/店铺开通提示；code=0 的空数组或 null 表示无提示，仍需独立查询账户状态。仅 popupType=1 且 isForceUse=1 作为设备强制开通要求；普通 1/4 为可继续的提示，不是未签约证明。其余提示不用于购买 |
| alipay/isSign | 原版账户免密状态查询，空业务参数、严格 Boolean 回复；不添加未证实的 merchant 参数。账户已签不能覆盖设备明确的强制开通要求 |
| alipay/signUrl/pinpoint | 仅需要开通交接时，用平台给出的真实 tradeMain 获取签约入口；它不是签约状态查询。非空官方链接交给支付宝，空链接不能清除已有开通要求 |
| payChannelRoute/addUserAfterPayChannel | 签约无需交接后提交实际所选 method；支付宝免密 15 不改写为余额 2；成功回复的 null/简单值仅是渠道偏好确认，不是签约凭据 |
| goods/water/unlock | skuId、promotions；头部传 imei、categoryCode、lat、lng，并保留已有请求签名 |
| machine/msg/sync | msgId；指令确认只用于跟进状态，不代替设备运行确认 |
| goods/water/sync | skuId；workStatus=1 可用、2 当前账户使用、3 他人使用 |
| goods/endWater | goodsId；再次确认当前账户的会话，且设备支持 supportAppUserFinish 才调用 |
| order/afterPay/creating | orderNo=同步结果中的 identify；按原客户端的轮询契约取得结算账单 |
| order/detail | 校验返回账单编号、与本次选择相符的余额/支付宝类型及实际 payPrice，保留已支付和未支付账单；由当前账号设备状态恢复的会话按账单核对支付方式 |
| payChannelRoute/againPay | 用户付款时使用 orderNo、confirmSelectedStatus=false 重新核对支付宝单次支付渠道 13，不把后付费渠道 15 直接当作 SDK 支付参数 |

promotions 使用原客户端未选优惠/积分时的 -6、-7、-8 排除项，不购买水卡、会员或附加商品。用户确认开始使用时才提交所选支付方式及发起启动请求。

原版 AfterPayUseAct 会把支付宝渠道的 needOpenFlag 用于小票优先偏好，该字段不能单独被视为支付宝渠道不可用或已经授权的证据。原枚举中 15 为支付宝免密，13 为普通支付宝，103 为 POST_ALIPAY；只有 15 进入本次签约准备分支。会员、不可选及第三方卡渠道继续排除。

## 免密签约交接

- `tradeMain` 来自设备真实详情，不使用猜测的固定数字或设备类别。开始用水前重新读取设备；旧缓存缺少字段时重新获取，仍缺失则停止。同一设备的开通提示可按原版提供签约入口的 `tradeMain`，但不能替换设备身份；出现互相冲突的商户要求时停止。
- 原 `AfterPayUseAct.useBtnClick` 的普通渠道 15 查询 `popup/shop`，`onResume` 也刷新该店铺信息；`aliSignUrl` 只由开通分支触发。3.0.0 误将 `pinpoint` 的非空链接当作未签约，4.0.0 已移除这个判断。现在结合原版账户 Boolean 状态和设备强制要求，仅需要交接时生成链接。`isSign` 未证实具有当前商户作用域，因此普通提示不会作为商户未签约证据，也不写入永久签约标志。
- 原版普通免密弹窗允许继续使用。返回后仅内存保留本次交接上下文；同一账户、设备、店铺、设备商户字段、实际签约商户及渠道的下一次显式确认可继续通过普通提示，仍重查账户状态、设备强制要求和启动回复。实际签约商户由返回上下文一直传到 API 核对，新商户不能复用旧商户交接。该资格使用一次即清除，扫码/登出时清除，不能覆盖强制开通、未知状态或平台拒绝，不代表签约成功。
- 返回后的只读查询遇到网络连接失败时最多尝试三次，重试前等待 500/1000ms；业务拒绝、响应异常不自动重试。不会重试渠道选择、签约交接或出水指令。手动刷新同步更新授权提示，查询失败仍显示状态未确认，不称为未签约。
- 官方 `alipays://platformapi/startapp` 链接只交给已安装的支付宝。支付宝官方 HTTPS 链接保留原始查询参数，通过现有 SDK `OpenAuthTask.BizType.Deduct` 的 `sign_params` 打开；签约不复用订单 `PayTask.payV2`。回调 scheme 为 `jianxi-alipay-deduct`，使用 SDK 已有的 `AlipayResultActivity`。
- 交接只由用户确认用水触发，支付宝的协议与最终开通须由用户本人确认。签约桥返回或取消后，重新获取同一设备详情并核对账户、设备标识、机构与商户，再查询账户状态及设备要求；不再次生成签约链接，不依赖 SDK 9000 或返回前台推断签约完成，不自动解锁或重新出水。
- 签约 URL 仅在本次交接期间内存传递，不写入会话、业务日志或源码。未安装支付宝、旧回调、账户/设备切换和信息变化均不会触发用水。

已有支付宝授权由平台按实际用量结算扣款。尚未支付的账单只在用户确认付款后获取单次支付信息并调用现有支付宝 SDK。本次不接入会员/周期扣款营销流程。

同步 status=3/5 表示结束，status=6 表示异常。启动前保存意图，网络失败后保留 UNKNOWN 状态，只查询和恢复。旧的已完成记录不能证明新启动已结束；保留启动前的 identify，并核对是否观察到本次运行或新的使用标识。不会结束 workStatus=3 的他人会话。

status 是用水结果，workStatus 是当前出水口占用状态。启动前以 workStatus=1、设备详情无错误以及出水口/计费未变化共同确认空闲；上次结果为 6 不会把已经空闲的设备永久标为硬件故障。进行中会话的 status=6 仍保留 UNKNOWN，不会重发启动。明确在渠道准备阶段失败、尚未发送 unlock 的请求标记 REJECTED，允许刷新支付渠道后重试。

会话按当前登录账号分别加密保存。页面可见且应用处于前台时每三秒查询进行中的会话，离开页面或进入后台后停止定时查询。结算账单不会再次发送设备同步或启动指令。

4.1.0 起，交易记录与当前扫码选择分开：启动或登录不把记录中的设备/出水口填入饮水页，空闲页再次进入需重新扫描。只有明确打开进行中的订单记录才恢复操作设备，已结束记录仅作历史展示。后台轮询不使用整页 busy/progress，旧回复须通过前台操作代次及账户/设备/页面检查，不能覆盖之后的结束用水或导航结果。

## 验证范围

本地 MockWebServer 合同测试及纯状态测试覆盖设备类别、费率精度、真实字段名、余额渠道、超时后不重发、重复点击、旧记录、他人占用、远程结束支持和实际账单校验。Android 模拟器测试使用完全拦截网络的本地响应，不向正式服务发起扣款或启动请求。

联网真机的二维码兼容性、服务器字段的实际变体、支付宝真实签约回跳、出水与扣款结果仍需要现场确认。蓝牙连接、先付费饮水和云闪付通道没有实现。
