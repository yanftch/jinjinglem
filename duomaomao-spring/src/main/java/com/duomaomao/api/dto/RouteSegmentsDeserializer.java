package com.duomaomao.api.dto;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 把上游的 {@code routePlanResponse} 归一化为 {@code List<List<RoutePoint>>}。
 *
 * <ul>
 *   <li>单段：{@code [{lng,lat}, ...]} → {@code [[...]]}</li>
 *   <li>多段：{@code [[...],[...]]} → 原样</li>
 *   <li>null / [] → 空列表</li>
 * </ul>
 */
public class RouteSegmentsDeserializer extends JsonDeserializer<List<List<RoutePlanResult.RoutePoint>>> {

    @Override
    public List<List<RoutePlanResult.RoutePoint>> getNullValue(DeserializationContext context) {
        return List.of();
    }

    @Override
    public List<List<RoutePlanResult.RoutePoint>> deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode node = parser.readValueAsTree();
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        boolean nested = node.get(0).isArray();
        List<List<RoutePlanResult.RoutePoint>> segments = new ArrayList<>(nested ? node.size() : 1);
        if (nested) {
            for (JsonNode segment : node) {
                segments.add(readSegment(segment, context));
            }
        } else {
            segments.add(readSegment(node, context));
        }
        return segments;
    }

    private List<RoutePlanResult.RoutePoint> readSegment(JsonNode node, DeserializationContext context)
            throws IOException {
        if (!node.isArray()) {
            return List.of();
        }
        return context.readTreeAsValue(node,
                context.getTypeFactory().constructCollectionType(List.class, RoutePlanResult.RoutePoint.class));
    }
}
