package com.duomaomao.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * /planRoute202503an202609 与 /getPlanResultios202609.result 的回参。
 *
 * <p>上游的 {@code routePlanResponse} 有两种形状：单段是一维数组，带途经点时是二维数组。
 * 这里统一用 {@link RouteSegmentsDeserializer} 归一化成「段的列表」，因此本服务对外
 * 永远输出二维：{@code [[{lng,lat}, ...], ...]}。</p>
 *
 * <p>{@code isSuccessOnPlanRoute} 的语义是「当前进京证条件下是否存在可行路线」，
 * 与账号是否 VIP 无关。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoutePlanResult(
        @JsonProperty("isSuccessOnPlanRoute") boolean isSuccessOnPlanRoute,
        @JsonProperty("routePlanResponse")
        @JsonDeserialize(using = RouteSegmentsDeserializer.class)
        List<List<RoutePoint>> routePlanResponse,
        @JsonProperty("navigationSteps") List<NavigationStep> navigationSteps,
        @JsonProperty("distance") String distance,
        @JsonProperty("useTime") String useTime,
        @JsonProperty("canUseBaiDu") Boolean canUseBaiDu,
        @JsonProperty("IOSURI") String IOSURI,
        @JsonProperty("IOSURI2") String IOSURI2,
        @JsonProperty("isSuccessOnPlanRouteForEvery") List<Boolean> isSuccessOnPlanRouteForEvery) {

    /** 上游返回的是字符串坐标，保持原样，避免精度/格式被改写。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoutePoint(
            @JsonProperty("longitude") String longitude,
            @JsonProperty("latitude") String latitude) {
    }

    /**
     * 一个导航段。
     *
     * <p>高德透传的几个字段形状不稳定（同一次响应内既有字符串又有空数组）：
     * {@code action}、{@code assistant_action}、{@code toll_road}。实测
     * {@code action}/{@code assistant_action} 大多数是字符串，个别段是 {@code []}；
     * {@code toll_road} 大多是数组，个别段是字符串。因此这三个字段用
     * {@link JsonNode} 原样透出，不再强转类型。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NavigationStep(
            @JsonProperty("instruction") String instruction,
            @JsonProperty("orientation") String orientation,
            @JsonProperty("distance") String distance,
            @JsonProperty("tolls") String tolls,
            @JsonProperty("toll_distance") String tollDistance,
            @JsonProperty("road") String road,
            @JsonProperty("toll_road") JsonNode tollRoad,
            @JsonProperty("duration") String duration,
            @JsonProperty("polyline") String polyline,
            @JsonProperty("action") JsonNode action,
            @JsonProperty("assistant_action") JsonNode assistantAction,
            @JsonProperty("longStepVoice") JsonNode longStepVoice,
            @JsonProperty("tmcs") List<Tmc> tmcs,
            @JsonProperty("cities") List<City> cities) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tmc(
            @JsonProperty("lcode") List<String> lcode,
            @JsonProperty("distance") String distance,
            @JsonProperty("status") String status,
            @JsonProperty("polyline") String polyline) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record City(
            @JsonProperty("name") String name,
            @JsonProperty("citycode") String citycode,
            @JsonProperty("adcode") String adcode,
            @JsonProperty("districts") List<District> districts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record District(
            @JsonProperty("name") String name,
            @JsonProperty("adcode") String adcode) {
    }
}
