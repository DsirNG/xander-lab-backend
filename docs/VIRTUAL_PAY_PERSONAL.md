# 个人主体小程序虚拟支付部署说明

本接入只使用道具直购 `short_series_goods`，订阅等级固定为：

`FREE < PLUS < PRO < ULTRA`

客户端支付成功回调不发放权益。权益只由微信发货推送或服务端 `query_order` 查到状态
`2（已支付待发货）/3（发货中）/4（已发货）` 后幂等发放。

## 部署顺序

1. 人工应用 `src/main/resources/db/virtual_pay_migration.sql`。
2. 在 MP 后台发布 PLUS、PRO、ULTRA 三个道具，价格单位为分，ID 与环境变量完全一致。
3. 设置环境变量；保持 `WECHAT_VIRTUAL_PAY_ENABLED=false` 启动并检查配置。
4. 在 MP 后台配置明文消息推送 URL：
   `https://api.dinqor.cn/api/virtual-pay/notify`，Token 与
   `WECHAT_VIRTUAL_PAY_MESSAGE_TOKEN` 一致。
5. URL 校验成功后设置 `WECHAT_VIRTUAL_PAY_ENABLED=true` 并重启服务。
6. 使用小额现网真单完成支付、推送、发货、查单和账单核对。

密钥不得写入仓库、小程序包、请求参数或日志。当前接收器采用 HTTPS + 微信明文消息签名；MP
后台的消息加密方式应选明文模式，不能选择兼容或安全模式。

## 部署前检查清单

- [ ] 个人主体、中国大陆居民身份证、工具类目、认证和备案均已满足
- [ ] 已知晓全终端月支付限额 10 万元
- [ ] MP 后台虚拟支付已开通并完成签约
- [ ] AppID、OfferID、现网 AppKey 已准备；AppKey 仅在服务器环境变量
- [ ] PLUS、PRO、ULTRA 道具已发布，ID 和价格与后端配置一致
- [ ] 如需 iOS：已配置小程序简称并开通 Apple IAP；微信版本要求不低于 8.0.68
- [ ] 数据库迁移已应用，`virtual_pay_order` 唯一索引存在
- [ ] 发货推送 URL 已配置为明文模式，URL 校验通过
- [x] 双签名实现：paySig 使用 AppKey，signature 使用本次 code2session 的 session_key
- [x] 客户端每次支付重新 wx.login，session_key 不落库、不下发
- [x] 发货按微信单号/业务单号条件更新，重复通知不重复发权益
- [x] `query_order` 使用 `order_id`，严格按嵌套 `order.status` 判断并校验实付金额
- [x] 前端已接 `wx.requestVirtualPayment`，客户端 success 不直接发权益
- [ ] 小额真单已验证：支付 → 推送 → 发货 → 用户等级/有效期 → MP 后台账单一致
- [ ] 已向用户说明退款、结算与费率：Android 等 T+3/1%，iOS 约45-60天/12%
