package com.duomaomao.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * /getUserInfo202609 的回参。
 *
 * <p>注意：服务端会为任意 OPENID 自动建档，新号/匿名号会拿到 1~2 天试用 VIP
 * （{@code isVIP=true}），所以这个字段不能当作「真人付费会员」的可靠判据。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserInfo(
        @JsonProperty("userId") String userId,
        @JsonProperty("isVIP") boolean isVip,
        @JsonProperty("VIPDeadline") String vipDeadline,
        @JsonProperty("isNew") boolean isNew,
        @JsonProperty("buy_times") Integer buyTimes,
        @JsonProperty("isTemp") Boolean isTemp) {
}
