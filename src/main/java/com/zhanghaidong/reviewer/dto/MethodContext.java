package com.zhanghaidong.reviewer.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个被改方法的上下文信息
 *
 * @author 张海东
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MethodContext {

    /** 方法签名,例如 "public List<User> findByName(String name)" */
    private String signature;

    /** 方法在文件中的起止行号 */
    private int startLine;
    private int endLine;

    /** 方法完整源码 */
    private String body;
}