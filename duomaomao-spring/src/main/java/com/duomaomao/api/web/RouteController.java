package com.duomaomao.api.web;

import com.duomaomao.api.dto.PlanResult;
import com.duomaomao.api.dto.RoutePlanRequest;
import com.duomaomao.api.dto.RoutePlanResult;
import com.duomaomao.api.service.DuomaomaoService;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/routes", produces = MediaType.APPLICATION_JSON_VALUE)
public class RouteController {

    private final DuomaomaoService service;

    public RouteController(DuomaomaoService service) {
        this.service = service;
    }

    /**
     * 路线规划。
     *
     * <p>会消耗上游（高德）额度：单次 1~43 s。本服务对相同参数做了 30 分钟缓存，
     * 并且限制同时最多 N 个请求在飞，排队超时返回 429。</p>
     */
    @PostMapping(path = "/plan", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RoutePlanResult plan(@Valid @RequestBody RoutePlanRequest request) {
        return service.planRoute(request);
    }

    /** 取该 OPENID 最后一次规划结果；没有结果时返回 {"isSuccess":false}。 */
    @GetMapping("/result")
    public PlanResult result(@RequestParam(required = false) String openid) {
        return service.planResult(openid);
    }
}
