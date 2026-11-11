package com.github.yingli.adhoc.exception;

/**
 * @Description Ad-hoc 链路可读异常：code 供机器判别，message 为中文原因供模型如实转述。
 * @Author crm-agent
 */
public class AdhocException extends RuntimeException {

    private final String code;

    public AdhocException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
