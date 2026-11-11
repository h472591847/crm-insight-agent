package com.itzixi.utils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author oliver
 * @Description: 自定义响应数据结构
 *              200 表示成功
 *              500 表示错误, 错误信息会保存到msg字段中
 *              501 bean验证错误, 不管多少错误都以map形式返回
 *              502 拦截器拦截到用户token出错
 *              555 异常抛出信息
 *              556 用户qq校验异常
 *              557 校验用户是否在CAS登录, 用户门票的校验
 * @Version 1.0
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LeeResult {

    // 响应业务状态
    private Integer status;

    // 响应消息
    private String msg;

    // 响应中的数据
    private Object data;

    private String ok;

    public static LeeResult build(Integer status, String message, Object data) {
        return new LeeResult(status, message, data);
    }

    public static LeeResult build(Integer status, String message, Object data, String ok) {
        return new LeeResult(status, message, data,ok);
    }

    public static LeeResult ok(Object data) {
        return new LeeResult(data);
    }

    public static LeeResult ok() {
        return new LeeResult(null);
    }

    public static LeeResult errorMsg(String msg) {
        return new LeeResult(500, msg, null);
    }

    public static LeeResult errorUserTicket(String msg) {
        return new LeeResult(557, msg, null);
    }

    public static LeeResult errorMap(Object data) {
        return new LeeResult(501, "error", data);
    }

    public static LeeResult errorTokenMsg(String msg) {
        return new LeeResult(502, msg, null);
    }

    public static LeeResult errorException(String msg) {
        return new LeeResult(555, msg, null);
    }

    public static LeeResult errorUserQQ(String msg) {
        return new LeeResult(556, msg, null);
    }

    public LeeResult(Integer status, String message, Object data) {
        this.status = status;
        this.msg = message;
        this.data = data;
    }

    public LeeResult(Object data) {
        this.status = 200;
        this.msg = "OK";
        this.data = data;
    }

    public Boolean isOK() {
        return this.status == 200;
    }

}
