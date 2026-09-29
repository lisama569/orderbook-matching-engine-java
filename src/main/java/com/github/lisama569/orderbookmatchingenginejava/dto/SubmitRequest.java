package com.github.lisama569.orderbookmatchingenginejava.dto;

import com.github.lisama569.orderbookmatchingenginejava.model.Side;

/** 下单请求。limitPrice 为可选（市价单省略）；均以 long 计价（8 位小数定标）。 */
public record SubmitRequest(String clientOrderId, String account, String symbol,
                            Side side, String type, Long limitPrice, Long qty) {
}
