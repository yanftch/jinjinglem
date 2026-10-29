package com.duomaomao.api.web;

import java.util.Map;

import com.duomaomao.api.dto.AppUpdate;
import com.duomaomao.api.dto.CameraComments;
import com.duomaomao.api.dto.CameraPoint;
import com.duomaomao.api.dto.GaodeKeys;
import com.duomaomao.api.dto.LabelsResult;
import com.duomaomao.api.dto.TokenValidateRequest;
import com.duomaomao.api.dto.TokenValidation;
import com.duomaomao.api.dto.UserInfo;
import com.duomaomao.api.service.DuomaomaoService;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ApiController {

    private final DuomaomaoService service;

    public ApiController(DuomaomaoService service) {
        this.service = service;
    }

    /** 版本信息 + 服务端下发的端点路径。 */
    @GetMapping("/update")
    public AppUpdate update() {
        return service.update();
    }

    /** 只取路径表，方便调试「上游改了路由」。 */
    @GetMapping("/paths")
    public Map<String, String> paths() {
        return service.paths();
    }

    /**
     * 全量探头。
     *
     * @param page    1 或 2；服务端 page&gt;=3 会回落第 1 页
     * @param refresh true 时跳过本地缓存（上游仍可能有自己的缓存）
     */
    @GetMapping("/labels")
    public LabelsResult labels(@RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "false") boolean refresh) {
        return service.labels(page, refresh);
    }

    /** 按 id 查探头（内部走全量列表，命中 labels 缓存）。 */
    @GetMapping("/cameras/{id}")
    public CameraPoint camera(@PathVariable int id) {
        return service.findCamera(id).orElseThrow(() -> new CameraNotFoundException(id));
    }

    /** 高德 Key。 */
    @GetMapping("/gaode-keys")
    public GaodeKeys gaodeKeys() {
        return service.gaodeKeys();
    }

    /** 用户信息；不传 openid 时上游会返回 temp_undefined 临时用户。 */
    @GetMapping("/users")
    public UserInfo user(@RequestParam(required = false) String openid) {
        return service.userInfo(openid);
    }

    /**
     * 探头留言。注意：id 不存在时上游返回的是「全站最新 7 条留言」，
     * 此时 cameraName 为空串，调用方需要自行判断。
     */
    @GetMapping("/comments/{id}")
    public CameraComments comments(@PathVariable int id) {
        return service.comments(id);
    }

    /** 校验用户登录 token（不是本服务自己的鉴权）。 */
    @PostMapping(path = "/tokens/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TokenValidation validate(@Valid @RequestBody TokenValidateRequest request) {
        return service.validateToken(request.token(), request.deviceIdOrDefault());
    }
}
