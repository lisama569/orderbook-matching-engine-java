package com.github.lisama569.orderbookmatchingenginejava.model;

/**
 * 一笔成交记录（事件重放的最小结算单元）。
 *
 * @param tradeId    全局成交号（等于事件序号，保证重放确定）
 * @param symbol     合约
 * @param price      成交价格（最小报价单位的整数倍）
 * @param qty        成交数量
 * @param buyOrderId 买方订单号
 * @param sellOrderId 卖方订单号
 * @param buyAccount 买方账户
 * @param sellAccount 卖方账户
 * @param takerSide  主动方（吃单方）方向
 * @param eventSeq   对应事件序号
 */
public record Fill(long tradeId, String symbol, long price, long qty,
                   long buyOrderId, long sellOrderId,
                   String buyAccount, String sellAccount,
                   Side takerSide, long eventSeq) {
}
