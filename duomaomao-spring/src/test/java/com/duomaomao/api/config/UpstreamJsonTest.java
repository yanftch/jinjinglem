package com.duomaomao.api.config;

import java.util.List;

import com.duomaomao.api.dto.RoutePlanResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回归：真实 planRoute 响应里
 * {@code action}/{@code assistant_action} 有时是 {@code []}，
 * {@code toll_road} 有时是字符串。smoke test 曾因此整个解码失败。
 *
 * <p>两层防护：这三个字段用 {@code JsonNode} 原样透出；另外给上游解码器开了
 * ACCEPT_SINGLE_VALUE_AS_ARRAY，防止再出现别的「数组变标量」。</p>
 */
class UpstreamJsonTest {

    private static final String REAL_SHAPE = """
            {
              "isSuccessOnPlanRoute": true,
              "routePlanResponse": [{"longitude": "116.1", "latitude": "39.1"}],
              "navigationSteps": [
                {
                  "instruction": "向西行驶",
                  "road": "南二环",
                  "toll_road": [],
                  "action": "右转",
                  "assistant_action": "",
                  "tmcs": [],
                  "cities": []
                },
                {
                  "instruction": "上高速",
                  "toll_road": "东六环",
                  "action": [],
                  "assistant_action": [],
                  "tmcs": [],
                  "cities": []
                }
              ],
              "distance": "1.0km",
              "useTime": "1分钟"
            }
            """;

    @Test
    void keepsAmbiguousFieldsAsRawJson() throws Exception {
        RoutePlanResult result = UpstreamJson.lenient(new ObjectMapper())
                .readValue(REAL_SHAPE, RoutePlanResult.class);

        assertThat(result.isSuccessOnPlanRoute()).isTrue();
        assertThat(result.navigationSteps()).hasSize(2);

        RoutePlanResult.NavigationStep first = result.navigationSteps().get(0);
        assertThat(first.action().asText()).isEqualTo("右转");
        assertThat(first.assistantAction().asText()).isEmpty();
        assertThat(first.tollRoad().isArray()).isTrue();
        assertThat(first.tollRoad()).isEmpty();
        assertThat(first.road()).isEqualTo("南二环");

        RoutePlanResult.NavigationStep second = result.navigationSteps().get(1);
        assertThat(second.action().isArray()).isTrue();
        assertThat(second.tollRoad().asText()).isEqualTo("东六环");
    }

    @Test
    void lenientMapperAcceptsScalarWhereListExpected() throws Exception {
        // 高德 tmcs[].lcode 是字符串数组，偶尔只给一个字符串
        String json = """
                {"navigationSteps": [{"tmcs": [{"lcode": "abc", "distance": "10", "status": "畅通"}]}]}
                """;

        RoutePlanResult lenient = UpstreamJson.lenient(new ObjectMapper()).readValue(json, RoutePlanResult.class);
        assertThat(lenient.navigationSteps().get(0).tmcs().get(0).lcode()).containsExactly("abc");

        assertThatThrownBy(() -> new ObjectMapper().readValue(json, RoutePlanResult.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class)
                .hasMessageContaining("lcode");
    }

    @Test
    void doesNotMutateTheSharedMapper() {
        ObjectMapper base = new ObjectMapper();
        ObjectMapper lenient = UpstreamJson.lenient(base);

        assertThat(lenient).isNotSameAs(base);
        assertThat(base.isEnabled(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)).isFalse();
        assertThat(lenient.isEnabled(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)).isTrue();
    }

    @Test
    void parsesRealUpstreamFixtureWithoutLosingShape() throws Exception {
        String json;
        try (var in = getClass().getResourceAsStream("/route-plan-real-shape.json")) {
            assertThat(in).isNotNull();
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        RoutePlanResult result = UpstreamJson.lenient(new ObjectMapper()).readValue(json, RoutePlanResult.class);

        // 带 2 个途经点 → routePlanResponse 是三段二维数组
        assertThat(result.routePlanResponse()).hasSize(3);
        assertThat(result.isSuccessOnPlanRouteForEvery()).hasSize(3).containsOnly(true);
        assertThat(result.distance()).isNotBlank();
        assertThat(result.useTime()).isNotBlank();

        List<RoutePlanResult.NavigationStep> steps = result.navigationSteps();
        assertThat(steps).hasSize(4);
        assertThat(steps).anySatisfy(step -> assertThat(step.action()).isEmpty());
        assertThat(steps).anySatisfy(step -> assertThat(step.tollRoad().isTextual()).isTrue());
        assertThat(steps).anySatisfy(step -> assertThat(step.tollRoad().isArray()).isTrue());
        assertThat(steps.get(0).tmcs()).isNotNull();
        assertThat(steps.get(0).cities()).isNotEmpty();
    }

    @Test
    void keepsMultiSegmentShapeAfterLenientCopy() throws Exception {
        String json = """
                {"routePlanResponse": [[{"longitude":"1","latitude":"2"}],[{"longitude":"3","latitude":"4"}]]}
                """;

        RoutePlanResult result = UpstreamJson.lenient(new ObjectMapper()).readValue(json, RoutePlanResult.class);

        assertThat(result.routePlanResponse()).hasSize(2);
        assertThat(result.routePlanResponse().get(1)).isEqualTo(
                List.of(new RoutePlanResult.RoutePoint("3", "4")));
    }
}
