package com.duomaomao.api.web;

/** /api/v1/cameras/{id} 找不到对应探头。 */
public class CameraNotFoundException extends RuntimeException {

    public CameraNotFoundException(int id) {
        super("未找到 id=" + id + " 的探头");
    }
}
